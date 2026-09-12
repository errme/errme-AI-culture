package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.mapper.RecycleMapper;
import com.culture.service.RecycleService;
import com.culture.util.PageList;
import com.culture.util.SearchUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收站服务实现。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>一次查完</b>：角标用 RecycleMapper.countAll() 的单条 SQL（7 个标量子查询），
 *       不存在「先查总数再逐个类型查」的 N+1；</li>
 *   <li><b>响应行结构固定</b>：MyBatis 对 Map 结果默认不写入 null 列（callSettersOnNulls=false），
 *       所以这里统一归一化成 id/title/summary/extra/createTime/deletedTime 六个键，前端不用判 key 是否存在；</li>
 *   <li><b>时间格式化</b>：统一输出 'yyyy-MM-dd HH:mm:ss' 字符串（避免依赖全局 Jackson 时间戳配置），
 *       SimpleDateFormat 非线程安全，故每次 list 调用内部新建一个实例，不共享；</li>
 *   <li><b>deletedTime 的口径</b>：有 updated_at 的表（culture/category/announcement/sentence/user）
 *       取 updated_at（逻辑删除时 ON UPDATE CURRENT_TIMESTAMP 会刷新）；
 *       <b>biz_tag / biz_comment 表没有 updated_at 列</b>，只能退化为 created_at，
 *       前端若要精确的删除时间需要先补列（属于表结构变更，本次不做，见 RecycleMapper 注释）。</li>
 * </ul>
 */
@Service
public class RecycleServiceImpl implements RecycleService {

    private static final String TYPE_CULTURE = "culture";
    private static final String TYPE_CATEGORY = "category";
    private static final String TYPE_TAG = "tag";
    private static final String TYPE_ANNOUNCEMENT = "announcement";
    private static final String TYPE_SENTENCE = "sentence";
    private static final String TYPE_COMMENT = "comment";
    private static final String TYPE_USER = "user";

    /** 支持的类型（同时也是 /recycle/counts 响应的键顺序） */
    private static final String[] TYPES = {
            TYPE_CULTURE, TYPE_CATEGORY, TYPE_TAG, TYPE_ANNOUNCEMENT,
            TYPE_SENTENCE, TYPE_COMMENT, TYPE_USER
    };

    /** 分页兜底：pageSize 缺省 10，最大 100 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /** 彻底删除一次最多 200 条（比逻辑删除的 500 更保守：物理删除不可恢复） */
    private static final int MAX_PURGE_SIZE = 200;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private RecycleMapper recycleMapper;

    /** 前台公共列表读缓存（分类 / 标签）：彻底删除可能改变标签内容数，见 purge 里的失效逻辑 */
    @Autowired
    private com.culture.service.CacheService cacheService;

    // ===================== 1. 角标 =====================

    @Override
    public Map<String, Object> counts() {
        Map<String, Object> raw = recycleMapper.countAll();
        Map<String, Object> data = new LinkedHashMap<>();
        for (String type : TYPES) {
            long n = (raw == null) ? 0L : toLong(raw.get(type));
            data.put(type, n);
        }
        return data;
    }

    // ===================== 2. 分页列表 =====================

    @Override
    public PageList list(String type, String keyword, Integer page, Integer pageSize) {
        String t = requireType(type);

        int p = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;
        // 用 long 计算偏移，避免 page 很大时 int 溢出成负数导致 SQL 报错
        long offsetL = (long) (p - 1) * (long) size;
        int offset = offsetL > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offsetL;

        // 关键词：先 trim + 截断 50 字，再转义 LIKE 通配符（% _ !），空关键词转成 null 不拼条件
        String kw = SearchUtil.toLikePattern(keyword);
        if (kw.isEmpty()) kw = null;

        Long total = countByType(t, kw);
        List<Map<String, Object>> rawRows = listByType(t, kw, offset, size);

        PageList pageList = new PageList();
        pageList.setTotal(total == null ? Long.valueOf(0L) : total);
        pageList.setRows(toRows(rawRows));
        return pageList;
    }

    /** 按类型分发 count（类型已在 requireType 校验过，兜底分支只为编译通过） */
    private Long countByType(String type, String kw) {
        switch (type) {
            case TYPE_CULTURE:
                return recycleMapper.countCulture(kw);
            case TYPE_CATEGORY:
                return recycleMapper.countCategory(kw);
            case TYPE_TAG:
                return recycleMapper.countTag(kw);
            case TYPE_ANNOUNCEMENT:
                return recycleMapper.countAnnouncement(kw);
            case TYPE_SENTENCE:
                return recycleMapper.countSentence(kw);
            case TYPE_COMMENT:
                return recycleMapper.countComment(kw);
            case TYPE_USER:
                return recycleMapper.countUser(kw);
            default:
                throw new BusinessException("不支持的回收站类型：" + type);
        }
    }

    /** 按类型分发分页数据 */
    private List<Map<String, Object>> listByType(String type, String kw, int offset, int pageSize) {
        switch (type) {
            case TYPE_CULTURE:
                return recycleMapper.listCulture(kw, offset, pageSize);
            case TYPE_CATEGORY:
                return recycleMapper.listCategory(kw, offset, pageSize);
            case TYPE_TAG:
                return recycleMapper.listTag(kw, offset, pageSize);
            case TYPE_ANNOUNCEMENT:
                return recycleMapper.listAnnouncement(kw, offset, pageSize);
            case TYPE_SENTENCE:
                return recycleMapper.listSentence(kw, offset, pageSize);
            case TYPE_COMMENT:
                return recycleMapper.listComment(kw, offset, pageSize);
            case TYPE_USER:
                return recycleMapper.listUser(kw, offset, pageSize);
            default:
                throw new BusinessException("不支持的回收站类型：" + type);
        }
    }

    /**
     * 把 Mapper 的原始 Map 归一化成固定 6 键的响应行。
     * 时间统一格式化为 'yyyy-MM-dd HH:mm:ss' 字符串，避免前端拿到时间戳还要自己格式化。
     */
    private List<Map<String, Object>> toRows(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (rawRows == null || rawRows.isEmpty()) {
            return rows;
        }
        SimpleDateFormat fmt = new SimpleDateFormat(DATE_PATTERN);
        for (Map<String, Object> raw : rawRows) {
            if (raw == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            Object id = raw.get("id");
            row.put("id", id == null ? null : Long.valueOf(toLong(id)));
            row.put("title", toText(raw.get("title")));
            row.put("summary", toText(raw.get("summary")));
            row.put("extra", toText(raw.get("extra")));
            row.put("createTime", formatDate(raw.get("createTime"), fmt));
            row.put("deletedTime", formatDate(raw.get("deletedTime"), fmt));
            rows.add(row);
        }
        return rows;
    }

    // ===================== 3. 彻底删除（物理删除） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> purge(String type, List<Long> ids, Long currentUserId) {
        String t = requireType(type);
        List<Long> safeIds = normalizePurgeIds(ids);

        int count;
        int relationCount = 0;

        if (TYPE_CULTURE.equals(t)) {
            // 先清关联再删主表：关联清理的子查询要能读到「尚未被删掉」的 biz_culture 行
            relationCount = recycleMapper.purgeCultureTagByCultureIds(safeIds);
            count = recycleMapper.purgeCulture(safeIds);
        } else if (TYPE_TAG.equals(t)) {
            relationCount = recycleMapper.purgeCultureTagByTagIds(safeIds);
            count = recycleMapper.purgeTag(safeIds);
        } else if (TYPE_USER.equals(t)) {
            checkUserPurgeAllowed(safeIds, currentUserId);
            relationCount = recycleMapper.purgeUserRoleByUserIds(safeIds);
            count = recycleMapper.purgeUser(safeIds);
        } else if (TYPE_CATEGORY.equals(t)) {
            count = recycleMapper.purgeCategory(safeIds);
        } else if (TYPE_ANNOUNCEMENT.equals(t)) {
            count = recycleMapper.purgeAnnouncement(safeIds);
        } else if (TYPE_SENTENCE.equals(t)) {
            count = recycleMapper.purgeSentence(safeIds);
        } else if (TYPE_COMMENT.equals(t)) {
            count = recycleMapper.purgeComment(safeIds);
        } else {
            throw new BusinessException("不支持的回收站类型：" + type);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        data.put("relationCount", relationCount);

        // 彻底删除后同步失效前台公共列表缓存：
        //   · 分类：仅分类本体被物理删除时才可能影响 /api/culture/categorys；
        //   · 标签 / 文化：标签本体删除、或文化被物理删除时顺带清掉的 biz_culture_tag
        //     关联行，都会让「标签内容数 cultureCount」变化。
        // 这里只是「多做一次删除」，即使本不该失效也无副作用（下次读会回源一次）。
        if (cacheService != null
                && (TYPE_CATEGORY.equals(t) || TYPE_TAG.equals(t) || TYPE_CULTURE.equals(t))) {
            cacheService.evictCategoryList();
            cacheService.evictTagList();
        }
        return data;
    }

    /**
     * 用户物理删除的两条保护规则（都在任何删除之前判断，抛异常会回滚整个事务）：
     * <ol>
     *   <li>不能删当前登录账号自己（否则管理员会把自己踢出后台，且没有第二次机会）；</li>
     *   <li>不能删掉最后一个「管理员」角色用户：只统计「这批 id 里确实会被删掉
     *       （sys_user.deleted=1）的管理员」，用管理员总数做减法，结果为 0 就拒绝。
     *       注意：本接口只删 deleted=1 的行，所以正常在用的管理员本来就不会被删掉，
     *       这条规则真正防的是「最后一个管理员已经被逻辑删除，再被彻底删除」导致后台彻底没人管。</li>
     * </ol>
     */
    private void checkUserPurgeAllowed(List<Long> ids, Long currentUserId) {
        if (currentUserId != null && ids.contains(currentUserId)) {
            throw new BusinessException("不能彻底删除当前登录账号");
        }
        int purgeableAdmins = recycleMapper.countPurgeableAdminUsers(ids);
        if (purgeableAdmins <= 0) {
            return;
        }
        long totalAdmins = recycleMapper.countAdminRoleUsers();
        if (totalAdmins - purgeableAdmins < 1L) {
            throw new BusinessException("不能彻底删除最后一个「管理员」角色账号");
        }
    }

    /** 彻底删除的 id 规整：去 null、去重，非空且不超过 200 条 */
    private List<Long> normalizePurgeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        if (safeIds.size() > MAX_PURGE_SIZE) {
            throw new BusinessException("一次最多彻底删除 " + MAX_PURGE_SIZE + " 条");
        }
        return safeIds;
    }

    // ===================== 4. 小工具 =====================

    /** 类型白名单校验：非法/缺省一律 BusinessException（控制层转 400，不是 500） */
    private String requireType(String type) {
        String t = type == null ? null : type.trim();
        if (t == null || t.isEmpty()) {
            throw new BusinessException("参数错误：缺少类型 type");
        }
        for (String supported : TYPES) {
            if (supported.equals(t)) {
                return t;
            }
        }
        throw new BusinessException("不支持的类型：" + type);
    }

    /** null 安全的数值转换（BIGINT UNSIGNED 可能被驱动映射成 Long / BigInteger） */
    private long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 空串按 null 输出：concat_ws 全 null 时会返回空串，统一成 null 更利于前端判断 */
    private String toText(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    /** 时间格式化：Date/Timestamp → 'yyyy-MM-dd HH:mm:ss'；其它类型原样字符串化 */
    private Object formatDate(Object v, SimpleDateFormat fmt) {
        if (v == null) return null;
        if (v instanceof Date) return fmt.format((Date) v);
        return String.valueOf(v);
    }
}
