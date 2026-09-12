package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Culture;
import com.culture.entity.Tag;
import com.culture.mapper.TagMapper;
import com.culture.service.TagService;
import com.culture.service.ThumbnailService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签服务实现。
 * 说明：标签名称为空/超长、重名都会抛 BusinessException，由控制层转成 ApiResult.error。
 */
@Service
public class TagServiceImpl implements TagService {

    /** 标签名最大长度（与 biz_tag.name varchar(50) 对齐） */
    private static final int MAX_NAME_LEN = 50;
    /** slug 最大长度（与 biz_tag.slug varchar(80) 对齐） */
    private static final int MAX_SLUG_LEN = 80;
    /** 分页上限，避免 pageSize 被放大导致全表扫描 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 单次批量操作上限（与前端一次最多选 500 条对齐） */
    private static final int MAX_BATCH_SIZE = 500;

    @Autowired
    private TagMapper tagMapper;

    /** 列表缩略图派生（A3）：标签页列表卡片改用 thumbnail，详情页仍为原图 */
    @Autowired
    private ThumbnailService thumbnailService;

    /**
     * 前台标签列表的读缓存（见 CacheService / ApiTagController#list）。
     * 标签本体或「内容-标签关联」发生任何变化都会让 cultureCount 变化，所以下面所有写路径
     * （保存/删除/批量删除/恢复/setCultureTags/合并/改名）结束后都要删 key。
     * 删除时机是「写库后立即删」，与并发读存在极短竞态，由 TTL 兜底。
     */
    @Autowired
    private com.culture.service.CacheService cacheService;

    /** 标签相关写操作后统一失效前台标签列表缓存（失败只记日志，不影响写结果） */
    private void evictFrontCache() {
        if (cacheService != null) {
            cacheService.evictTagList();
        }
    }

    @Override
    public List<Tag> queryAll() {
        List<Tag> tags = tagMapper.queryAll();
        // 一次性统计各标签的内容数并回填，避免前端/调用方对每个标签再查一次
        Map<Long, Integer> counts = new HashMap<>();
        for (Tag t : tagMapper.countCulturesGroupByTag()) {
            if (t.getId() != null && t.getCultureCount() != null) {
                counts.put(t.getId(), t.getCultureCount());
            }
        }
        for (Tag t : tags) {
            Integer c = counts.get(t.getId());
            t.setCultureCount(c == null ? 0 : c);
        }
        return tags;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Tag tag) {
        String name = tag.getName() == null ? "" : tag.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException("标签名称不能为空");
        }
        if (name.length() > MAX_NAME_LEN) {
            name = name.substring(0, MAX_NAME_LEN);
        }
        String slug = tag.getSlug() == null ? null : tag.getSlug().trim();
        if (slug != null && slug.length() > MAX_SLUG_LEN) {
            slug = slug.substring(0, MAX_SLUG_LEN);
        }
        if (slug != null && slug.isEmpty()) {
            slug = null;
        }
        tag.setName(name);
        tag.setSlug(slug);
        if (tag.getSort() == null) {
            tag.setSort(0);
        }

        // 重名校验：同名的另一条记录（含逻辑删除的）都不允许重复新增
        Tag sameName = tagMapper.findByName(name);
        boolean created = tag.getId() == null;
        if (sameName != null) {
            if (created || !sameName.getId().equals(tag.getId())) {
                throw new BusinessException("标签已存在");
            }
        }

        if (created) {
            if (sameName != null) {
                // 同名标签此前被逻辑删除：复活旧行，避免唯一键冲突
                tag.setId(sameName.getId());
                tagMapper.revive(tag);
            } else {
                tagMapper.insert(tag);
            }
        } else {
            if (tagMapper.findById(tag.getId()) == null) {
                throw new BusinessException("标签不存在");
            }
            tagMapper.update(tag);
        }

        // 新增/编辑都可能改变前台标签列表（名称、排序、内容数），统一失效
        evictFrontCache();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", tag.getId());
        data.put("name", tag.getName());
        data.put("created", created);
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误");
        }
        // 先解除文化关联，再逻辑删除标签本体
        tagMapper.deleteCultureTags(id);
        tagMapper.logicalDelete(id);
        evictFrontCache();
    }

    @Override
    public List<Tag> tagsOfCulture(Long cultureId) {
        if (cultureId == null) return Collections.emptyList();
        return tagMapper.findTagsByCultureId(cultureId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setCultureTags(Long cultureId, List<Long> tagIds) {
        if (cultureId == null) {
            throw new BusinessException("参数错误");
        }
        tagMapper.deleteCultureTags(cultureId);
        if (tagIds == null || tagIds.isEmpty()) {
            // 内容标签被清空同样会改变各标签的 cultureCount，必须失效
            evictFrontCache();
            return;
        }
        for (Long tagId : tagIds) {
            if (tagId == null) continue;
            tagMapper.bindCulture(cultureId, tagId);
        }
        evictFrontCache();
    }

    @Override
    public Map<String, List<Tag>> tagsOfCultures(List<Long> cultureIds) {
        Map<String, List<Tag>> result = new LinkedHashMap<>();
        if (cultureIds == null || cultureIds.isEmpty()) {
            return result;
        }
        // 去重 + 初始化为空列表，保证调用方拿到的每个 id 都有值（前端不必判空）
        List<Long> ids = new ArrayList<>();
        for (Long id : cultureIds) {
            if (id == null || ids.contains(id)) continue;
            ids.add(id);
            result.put(String.valueOf(id), new ArrayList<Tag>());
        }
        if (ids.isEmpty()) {
            return result;
        }
        for (Tag t : tagMapper.findTagsByCultureIds(ids)) {
            List<Tag> list = result.get(String.valueOf(t.getCultureId()));
            if (list != null) {
                list.add(t);
            }
        }
        return result;
    }

    @Override
    public PageList culturesOfTag(Long tagId, Integer page, Integer pageSize) {
        PageList pageList = new PageList();
        if (tagId == null) {
            pageList.setTotal(0L);
            pageList.setRows(Collections.emptyList());
            return pageList;
        }
        int safePage = (page == null || page < 1) ? 1 : page;
        int safePageSize = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        Long total = tagMapper.countCulturesByTagId(tagId);
        pageList.setTotal(total == null ? 0L : total);

        List<Culture> rows = tagMapper.queryCulturesByTagId(tagId, offset, safePageSize);
        // A3：标签页列表卡片改用缩略图（fmUrl -> xxx_thumb.jpg，原图在 coverOriginal）
        thumbnailService.applyCoverThumb(rows);
        // 顺手把每个文化的标签带上（一次批量查询），前台列表可直接展示标签
        List<Long> ids = new ArrayList<>();
        for (Culture c : rows) ids.add(c.getId());
        Map<String, List<Tag>> tagMap = tagsOfCultures(ids);
        for (Culture c : rows) {
            c.setTags(tagMap.get(String.valueOf(c.getId())));
        }
        pageList.setRows(rows);
        return pageList;
    }

    // ===================== 后台批量操作（增量追加） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        List<Long> safeIds = normalizeIds(ids);
        // 与单条 delete 一致：先解除文化关联，再逻辑删除标签本体
        tagMapper.batchDeleteCultureTags(safeIds);
        int rows = tagMapper.batchLogicalDelete(safeIds);
        evictFrontCache();
        return rows;
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条标签：只把 biz_tag.deleted 改回 0。
     *
     * <p>【为什么不恢复文化关联】删除标签时 {@link TagMapper#deleteCultureTags} /
     * {@link TagMapper#batchDeleteCultureTags} 是对 biz_culture_tag 做<b>物理 delete</b>，
     * 关联数据已经不在库里，没有任何字段可以据此还原；
     * 因此恢复标签只让标签本体重新可见（内容数 0），原关联需要管理员在文化编辑页重新绑定。
     * 若产品后续要求「恢复标签连带恢复关联」，需要把关联表改成逻辑删除（加 deleted 列），
     * 属于表结构变更，本次不做。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少标签 id");
        }
        int rows = tagMapper.restoreById(id);
        evictFrontCache();
        return rows;
    }

    /** 批量恢复标签（同样只恢复标签本体，不恢复已物理删除的文化关联） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        int rows = tagMapper.restoreByIds(normalizeIds(ids));
        evictFrontCache();
        return rows;
    }

    // ===================== 标签合并 / 改名（增量追加） =====================

    /**
     * 标签合并：把源标签的文化关联全部改挂到目标标签，然后逻辑删除源标签。
     *
     * <p><b>冲突处理</b>：biz_culture_tag 的唯一键是 uk_culture_tag(culture_id, tag_id)。
     * 若某个文化同时挂了源、目标两个标签，改挂时必然撞唯一键，因此用
     * {@link TagMapper#moveCultureTags}（UPDATE IGNORE）先改挂不冲突的部分，
     * 冲突行被 MySQL 跳过保留在源标签下，随后由
     * {@link TagMapper#deleteCultureTagsByTagId} 物理删除 —— 效果就是「合并」，
     * 该文化最终只挂目标标签，不会产生重复关联，也不会丢文化。</p>
     *
     * <p><b>事务</b>：整个合并（改挂 + 删残留 + 逻辑删除源标签）在一个事务里，
     * 任何一步抛异常都整体回滚，不会出现「关联改了一半、源标签还在」的中间态。</p>
     *
     * <p><b>计数口径</b>：moved / merged 用「改挂前关联数 - 改挂后关联数」算出，
     * 不依赖 UPDATE 的返回行数（Connector/J 默认 CLIENT_FOUND_ROWS，返回的是匹配行数），
     * 因此恒有 moved + merged = 合并前源标签的关联总数。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> merge(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            throw new BusinessException("参数错误：请选择源标签和目标标签");
        }
        if (sourceId.equals(targetId)) {
            throw new BusinessException("源标签和目标标签不能相同");
        }
        // findById 只返回 deleted = 0 的行：不存在 / 已逻辑删除 都走同一句提示
        if (tagMapper.findById(sourceId) == null) {
            throw new BusinessException("源标签不存在或已删除");
        }
        if (tagMapper.findById(targetId) == null) {
            throw new BusinessException("目标标签不存在或已删除");
        }

        // 1) 改挂前的源关联总数
        int total = tagMapper.countCultureTagsByTagId(sourceId);
        // 2) 不冲突的关联直接改挂到目标标签（UPDATE IGNORE；冲突行被 MySQL 跳过、留在源标签下）。
        //    这里刻意不用 UPDATE 的返回行数：Connector/J 默认 CLIENT_FOUND_ROWS，返回的是匹配行数，
        //    会把被跳过的冲突行也算进去，用计数差更可靠。
        tagMapper.moveCultureTags(sourceId, targetId);
        // 3) 改挂后仍留在源标签下的，就只能是「该文化已挂过目标标签」的冲突行
        int merged = tagMapper.countCultureTagsByTagId(sourceId);
        int moved = total > merged ? total - merged : 0;
        // 4) 物理删除冲突残留（与标签删除语义一致：关联表不做逻辑删除），再逻辑删除源标签
        tagMapper.deleteCultureTagsByTagId(sourceId);
        tagMapper.logicalDelete(sourceId);
        // 源标签被并掉 + 目标标签内容数变化，前台标签列表必须失效
        evictFrontCache();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moved", moved);
        data.put("merged", merged);
        data.put("sourceId", sourceId);
        data.put("targetId", targetId);
        return data;
    }

    /**
     * 只改标签名。
     * 重名校验用 {@link TagMapper#findByName}（<b>不过滤 deleted</b>）：被逻辑删除的标签仍占着
     * 唯一键 uk_tag_name，若跳过它改名会直接撞唯一键抛 500。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rename(Long id, String name) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少标签 id");
        }
        String safeName = name == null ? "" : name.trim();
        if (safeName.isEmpty()) {
            throw new BusinessException("标签名称不能为空");
        }
        if (safeName.length() > MAX_NAME_LEN) {
            safeName = safeName.substring(0, MAX_NAME_LEN);
        }
        Tag sameName = tagMapper.findByName(safeName);
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new BusinessException("标签已存在");
        }
        if (tagMapper.findById(id) == null) {
            throw new BusinessException("标签不存在");
        }
        tagMapper.updateName(id, safeName);
        evictFrontCache();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", safeName);
        return data;
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一个标签");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一个标签");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 个标签");
        }
        return safeIds;
    }
}
