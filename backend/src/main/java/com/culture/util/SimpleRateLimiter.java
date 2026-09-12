package com.culture.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 极简限流器：Redis 计数 + 首次写入时设置 TTL（固定窗口）。
 *
 * 设计取舍：
 *   · 固定窗口（而非令牌桶）实现简单、对单机/多实例都有效，够用于「防脚本刷接口」；
 *   · **Redis 不可用时一律放行** —— 限流是保护措施，不能变成新的故障点；
 *   · 计数异常同样放行，避免因为缓存抖动把正常用户挡在外面。
 */
public class SimpleRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SimpleRateLimiter.class);

    private final StringRedisTemplate redisTemplate;

    public SimpleRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @param key      限流键（调用方拼好，例如 rl:/api/search:1.2.3.4）
     * @param limit    窗口内允许的次数
     * @param windowSec 窗口秒数
     * @return true=放行，false=超限
     */
    public boolean allow(String key, int limit, int windowSec) {
        if (redisTemplate == null || limit <= 0) return true;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, windowSec, TimeUnit.SECONDS);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("[rate-limit] Redis 计数失败，已放行：{}", e.getMessage());
            return true;
        }
    }
}
