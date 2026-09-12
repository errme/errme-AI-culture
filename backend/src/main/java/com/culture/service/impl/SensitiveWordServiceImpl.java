package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.SensitiveWord;
import com.culture.mapper.SensitiveWordMapper;
import com.culture.service.SensitiveWordService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 敏感词服务实现。
 *
 * <p><b>为什么用进程内缓存而不是 Redis</b>：词表是「读极多、写极少」的小表（通常几十行），
 * 评论提交每次都查库没必要；而 Redis 在本项目里只用于限流（见 CommentServiceImpl.checkRateLimit）
 * 且允许不可用降级，把敏感词表放 Redis 会引入「Redis 抖动 → 词表读不到」的新故障面。
 * 因此这里用最朴素的「volatile 引用 + 60 秒 TTL + 写操作主动失效」，不新增任何依赖。</p>
 *
 * <p><b>一致性代价（已知）</b>：多实例部署时，A 实例改了词表，B 实例最多 60 秒后才生效；
 * 单实例内写操作会立即失效缓存，但因为失效发生在事务提交前，理论上并发读者可能把
 * 「提交前的旧词表」重新缓存一次，同样最多 60 秒。敏感词过滤允许这种最终一致。</p>
 *
 * <p><b>降级</b>：加载词表抛异常一律返回 ACTION_NONE（放行），只打印日志 ——
 * 评论提交是主流程，不能因为词表故障被整体卡死。</p>
 */
@Service
public class SensitiveWordServiceImpl implements SensitiveWordService {

    /** 词最大长度（与 biz_sensitive_word.word varchar(100) 对齐） */
    private static final int MAX_WORD_LEN = 100;
    /** 备注最大长度（与 remark varchar(255) 对齐，超出直接截断） */
    private static final int MAX_REMARK_LEN = 255;
    /** 单次批量操作上限（与前端一次最多选 500 条对齐） */
    private static final int MAX_BATCH_SIZE = 500;
    /** 后台分页默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 后台分页每页上限，避免 pageSize 被放大导致全表扫描 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 启用词表缓存 TTL（毫秒）：60 秒 */
    private static final long CACHE_TTL_MILLIS = 60L * 1000L;

    @Autowired
    private SensitiveWordMapper sensitiveWordMapper;

    /** 启用词表缓存：只在同步块里整体替换引用，不做增量修改，故 volatile 读安全 */
    private volatile List<SensitiveWord> enabledCache;
    /** 缓存过期时间戳（毫秒）；enabledCache 为 null 或 now >= 该值即视为过期 */
    private volatile long cacheExpireAt = 0L;

    @Override
    public int matchAction(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return ACTION_NONE;
        }
        List<SensitiveWord> words;
        try {
            words = loadEnabled();
        } catch (Exception e) {
            // 降级放行：词表查询异常不影响评论提交（表不存在/库不可用都走这里）
            System.out.println("[sensitive] 词表加载失败（已放行）：" + e.getMessage());
            return ACTION_NONE;
        }
        if (words == null || words.isEmpty()) {
            return ACTION_NONE;
        }

        String text = plainText.toLowerCase(Locale.ROOT);
        boolean pending = false;
        for (SensitiveWord w : words) {
            if (w == null || w.getWord() == null) continue;
            String word = w.getWord().trim().toLowerCase(Locale.ROOT);
            if (word.isEmpty() || !text.contains(word)) continue;
            // 拒绝优先：命中任意一条 action=1 的词，整体判定为「直接拒绝」
            if (w.getAction() != null && w.getAction() == ACTION_REJECT) {
                return ACTION_REJECT;
            }
            pending = true;
        }
        return pending ? ACTION_PENDING : ACTION_NONE;
    }

    /**
     * 取启用词表（带 60 秒进程内缓存）。
     * 双检锁：正常路径只读 volatile，无锁无同步开销；缓存过期时才进同步块并查库一次。
     */
    private List<SensitiveWord> loadEnabled() {
        long now = System.currentTimeMillis();
        List<SensitiveWord> cached = this.enabledCache;
        if (cached != null && now < this.cacheExpireAt) {
            return cached;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (this.enabledCache != null && now < this.cacheExpireAt) {
                return this.enabledCache;
            }
            List<SensitiveWord> words = sensitiveWordMapper.findEnabled();
            List<SensitiveWord> safe = words == null
                    ? Collections.<SensitiveWord>emptyList() : words;
            this.enabledCache = safe;
            this.cacheExpireAt = now + CACHE_TTL_MILLIS;
            return safe;
        }
    }

    @Override
    public void refreshCache() {
        synchronized (this) {
            this.enabledCache = null;
            this.cacheExpireAt = 0L;
        }
    }

    // ===================== 后台维护 =====================

    @Override
    public PageList page(String keyword, Integer page, Integer pageSize) {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && kw.isEmpty()) {
            kw = null;
        }

        PageList pageList = new PageList();
        Long total = sensitiveWordMapper.queryTotal(kw);
        pageList.setTotal(total == null ? 0L : total);
        pageList.setRows(sensitiveWordMapper.queryData(kw, (safePage - 1) * safeSize, safeSize));
        return pageList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(SensitiveWord sensitiveWord) {
        if (sensitiveWord == null) {
            throw new BusinessException("参数错误");
        }
        String word = sensitiveWord.getWord() == null ? "" : sensitiveWord.getWord().trim();
        if (word.isEmpty()) {
            throw new BusinessException("敏感词不能为空");
        }
        if (word.length() > MAX_WORD_LEN) {
            // 词被截断会变成另一个词、判定结果不可预期，因此这里直接报错而不是截断
            throw new BusinessException("敏感词长度不能超过 " + MAX_WORD_LEN + " 个字符");
        }
        int action = sensitiveWord.getAction() == null ? ACTION_PENDING : sensitiveWord.getAction();
        if (action != ACTION_REJECT && action != ACTION_PENDING) {
            throw new BusinessException("命中处理只能是 1（直接拒绝）或 2（转待审核）");
        }
        int enabled = sensitiveWord.getEnabled() == null ? 1 : sensitiveWord.getEnabled();
        if (enabled != 0 && enabled != 1) {
            throw new BusinessException("启用状态只能是 0（停用）或 1（启用）");
        }
        String remark = sensitiveWord.getRemark() == null ? null : sensitiveWord.getRemark().trim();
        if (remark != null && remark.isEmpty()) {
            remark = null;
        }
        if (remark != null && remark.length() > MAX_REMARK_LEN) {
            remark = remark.substring(0, MAX_REMARK_LEN);
        }

        sensitiveWord.setWord(word);
        sensitiveWord.setAction(action);
        sensitiveWord.setEnabled(enabled);
        sensitiveWord.setRemark(remark);

        boolean created = sensitiveWord.getId() == null;
        if (!created && sensitiveWordMapper.findById(sensitiveWord.getId()) == null) {
            throw new BusinessException("敏感词不存在");
        }

        // 唯一性：uk_word 是唯一键，findByWord 故意不过滤 deleted（被逻辑删除的词仍占着名称），
        // 否则「删除后再新增同一个词」会直接撞唯一键抛 500。
        SensitiveWord sameName = sensitiveWordMapper.findByWord(word);
        if (sameName != null && (created || !sameName.getId().equals(sensitiveWord.getId()))) {
            if (created && sameName.getDeleted() != null && sameName.getDeleted() == 1) {
                // 同名词此前被逻辑删除：复活旧行，避免唯一键冲突
                sensitiveWord.setId(sameName.getId());
                sensitiveWordMapper.revive(sensitiveWord);
            } else {
                throw new BusinessException("敏感词已存在");
            }
        } else if (created) {
            sensitiveWordMapper.insert(sensitiveWord);
        } else {
            sensitiveWordMapper.update(sensitiveWord);
        }

        refreshCache();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", sensitiveWord.getId());
        data.put("word", sensitiveWord.getWord());
        data.put("created", created);
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少敏感词 id");
        }
        sensitiveWordMapper.logicalDelete(id);
        refreshCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        int count = sensitiveWordMapper.batchLogicalDelete(normalizeIds(ids));
        refreshCache();
        return count;
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误（而不是 500）。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一个敏感词");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一个敏感词");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 个敏感词");
        }
        return safeIds;
    }
}
