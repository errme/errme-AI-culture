package com.culture.service.impl;

import com.culture.mapper.SearchStatMapper;
import com.culture.service.SearchStatService;
import com.culture.util.SearchUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 热门搜索词 + 搜索历史实现（增量）。
 *
 * <p>写入路径全部 try/catch：统计/历史失败只降级为「少一条数据」，不影响搜索接口。
 * 热门词查询失败（例如没执行 docs/sql/10_search.sql，表不存在）返回空数组，
 * 前端拿到 {@code data: []} 后正常渲染空列表即可。</p>
 */
@Service
public class SearchStatServiceImpl implements SearchStatService {

    private static final Logger log = LoggerFactory.getLogger(SearchStatServiceImpl.class);

    /** 搜索历史在 Redis 里最多保留的条数（与 /api/search/history 的 limit 上限一致） */
    private static final int HISTORY_MAX_SIZE = 20;

    /** 搜索历史 key 前缀：search:history:{userId} */
    private static final String HISTORY_KEY_PREFIX = "search:history:";

    /** 热门词 / 历史的默认与上限条数（与 Controller 的收敛保持一致，防止内部调用越界） */
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    @Autowired
    private SearchStatMapper searchStatMapper;

    /** Redis 只用于搜索历史；未配置 Redis 时容器里没有这个 Bean，故 required=false 兜底 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Override
    public void recordKeyword(String keyword) {
        String kw = SearchUtil.normalizeKeyword(keyword);
        if (SearchUtil.lengthInCodePoints(kw) < SearchUtil.MIN_FULLTEXT_LENGTH) {
            return;
        }
        try {
            searchStatMapper.upsertKeyword(kw);
        } catch (Exception e) {
            // 表不存在时会持续失败，用 debug 记录即可（线上默认级别不会刷日志）
            if (log.isDebugEnabled()) {
                log.debug("热门搜索词统计失败，已忽略：keyword={}, cause={}", kw, e.getMessage());
            }
        }
    }

    @Override
    public List<Map<String, Object>> findHotKeywords(int limit) {
        try {
            List<Map<String, Object>> list = searchStatMapper.findHotKeywords(normalizeLimit(limit));
            return list == null ? Collections.<Map<String, Object>>emptyList() : list;
        } catch (Exception e) {
            // 表还没建 / 数据库异常：返回空数组，让前端优雅处理，接口不报错
            log.warn("热门搜索词查询失败，返回空列表：cause={}: {}", e.getClass().getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void recordHistory(Long userId, String keyword) {
        String kw = SearchUtil.normalizeKeyword(keyword);
        if (userId == null || redisTemplate == null
                || SearchUtil.lengthInCodePoints(kw) < SearchUtil.MIN_FULLTEXT_LENGTH) {
            return;
        }
        try {
            String key = HISTORY_KEY_PREFIX + userId;
            // 先去重再插队首：同一关键词重复搜索只把它「提到最前」，列表里不会出现重复项
            redisTemplate.opsForList().remove(key, 0, kw);
            redisTemplate.opsForList().leftPush(key, kw);
            redisTemplate.opsForList().trim(key, 0, HISTORY_MAX_SIZE - 1);
        } catch (Exception e) {
            // Redis 不可用：历史功能降级，不影响搜索
            if (log.isDebugEnabled()) {
                log.debug("搜索历史写入失败，已忽略：userId={}, cause={}", userId, e.getMessage());
            }
        }
    }

    @Override
    public List<String> findHistory(Long userId, int limit) {
        if (userId == null || redisTemplate == null) {
            return Collections.emptyList();
        }
        try {
            // 最新在前：LPUSH 写入 + range(0, n-1)
            List<String> list = redisTemplate.opsForList()
                    .range(HISTORY_KEY_PREFIX + userId, 0, normalizeLimit(limit) - 1);
            return list == null ? Collections.<String>emptyList() : list;
        } catch (Exception e) {
            log.warn("搜索历史读取失败，返回空列表：userId={}, cause={}: {}", userId,
                    e.getClass().getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 条数收敛：<=0 用默认 10，超过 20 截断到 20 */
    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
