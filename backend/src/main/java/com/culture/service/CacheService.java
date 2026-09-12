package com.culture.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 极简 Redis 读缓存（只缓存「前台公共只读列表」，不做通用缓存框架）。
 *
 * <p><b>为什么不用 Spring Cache 注解</b>：本项目是离线环境、不加新依赖；{@code @Cacheable} 需要
 * 额外的 CacheManager 配置与失效注解，反而更难保证「写操作一定失效」。这里用显式
 * {@link #getOrLoad} / {@link #evict} 两个方法，调用点一眼可见，失效逻辑跟写操作写在一起。</p>
 *
 * <p><b>缓存对象</b>（见 {@link #KEY_CATEGORY_LIST} / {@link #KEY_TAG_LIST} 的注释）：
 * 只缓存前台的**全量分类列表**与**全量标签列表**——这两个接口每次页面访问都要查、数据量大时
 * 属于典型的「读多写极少」，且内容与登录用户无关，可以安全共享。
 * 后台的同类接口（{@code /api/admin/tag/list} 等）**有意不走缓存**，保证管理员改完立刻看到最新数据。</p>
 *
 * <p><b>失效策略（两道保险）</b>：
 * <ol>
 *   <li>主动失效：所有写路径（新增/编辑/删除/批量删除/恢复/合并/改名/设置内容标签）调用
 *       {@link #evict} —— 这是主要手段，管理员改完前台立刻生效；</li>
 *   <li>TTL 兜底：默认 {@code app.cache.ttl-seconds=60}（可在 yml/环境变量调整），
 *       万一将来新增了没接失效的写路径，最多脏 60 秒，不会永久不一致。</li>
 * </ol>
 *
 * <p><b>失败一律降级</b>：Redis 未配置（{@code ObjectProvider} 取不到 Bean）、
 * {@code app.cache.enabled=false}、序列化/反序列化失败、连接异常 —— 任何一环出问题都只是
 * 「这次不走缓存」，直接调用 loader 查库，**绝不因此抛异常影响接口**。</p>
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    /** 前台分类列表：GET /api/culture/categorys（ApiHomeController#categorys） */
    public static final String KEY_CATEGORY_LIST = "culture:cache:front:category:list";
    /** 前台标签列表：GET /api/tag/list（ApiTagController#list，含 cultureCount） */
    public static final String KEY_TAG_LIST = "culture:cache:front:tag:list";
    /** 前台首页聚合：GET /api/home（公告 + 句子 + 热门文化 + 今日文化） */
    public static final String KEY_HOME = "culture:cache:front:home";
    /**
     * 后台菜单缓存的「版本号」。
     *
     * <p>菜单是**按用户**算出来的（用户 → 角色 → 菜单/权限）。角色授权一变，受影响的可能是
     * 几十个用户，逐用户删 key 既要知道「谁有这个角色」又容易漏；所以这里用「版本号」失效：
     * key 里带当前版本号 {@code culture:cache:menu:v<版本>:user:<id>}，
     * 任何授权变更只需 {@code INCR} 这个版本号，所有用户的旧 key 立刻不可达（由 TTL 自然回收）。
     * 这是缓存失效里最省事又不会漏的做法。</p>
     */
    public static final String KEY_MENU_EPOCH = "culture:cache:menu:epoch";

    /** 首页聚合的缓存时长（秒）：比分类/标签更短，因为它混了「浏览量/热门」这类持续变化的数据 */
    private static final long HOME_TTL_SECONDS = 30L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long ttlSeconds;

    public CacheService(ObjectProvider<StringRedisTemplate> redisProvider,
                        ObjectMapper objectMapper,
                        @Value("${app.cache.enabled:true}") boolean enabled,
                        @Value("${app.cache.ttl-seconds:60}") long ttlSeconds) {
        // 用 ObjectProvider：本机/测试环境没有 Redis 时也能正常启动（本项目 Redis 是可选依赖）
        this.redisTemplate = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 60L;
        if (!enabled) {
            log.info("[cache] 读缓存已关闭（app.cache.enabled=false），所有请求直查数据库");
        } else if (redisTemplate == null) {
            log.info("[cache] 未检测到 Redis，读缓存自动降级为直查数据库");
        } else {
            log.info("[cache] 读缓存已启用，TTL={}s", this.ttlSeconds);
        }
    }

    /**
     * 读缓存：命中直接返回；未命中调用 {@code loader} 并把结果写入 Redis（用默认 TTL）。
     *
     * @param key        缓存 key（用本类常量，避免手写字符串拼错）
     * @param loader     回源逻辑（查库）；**返回值必须可被 Jackson 序列化**
     * @param valueType  反序列化目标类型（Jackson 的 {@code TypeReference}，支持泛型）
     */
    public <T> T getOrLoad(String key, Supplier<T> loader, com.fasterxml.jackson.core.type.TypeReference<T> valueType) {
        return getOrLoad(key, ttlSeconds, loader, valueType);
    }

    /** 同 {@link #getOrLoad(String, Supplier, com.fasterxml.jackson.core.type.TypeReference)}，但可指定 TTL */
    public <T> T getOrLoad(String key, long ttl, Supplier<T> loader,
                           com.fasterxml.jackson.core.type.TypeReference<T> valueType) {
        if (!cacheUsable()) {
            return loader.get();
        }
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                // 空串是「空结果的占位」（见下面的写入分支），直接返回 null 表示「确实是空」
                if (cached.isEmpty()) {
                    return null;
                }
                return objectMapper.readValue(cached, valueType);
            }
        } catch (Exception e) {
            // 读缓存失败（连接断开/脏数据）不上升为业务错误：删掉脏 key 后回源
            log.warn("[cache] 读取失败，回源查询。key={}, err={}", key, e.toString());
            safeDelete(key);
            return loader.get();
        }

        T value = loader.get();
        try {
            // 注意：value 为 null 时也要占位（存空串），否则空结果会每次都回源（缓存穿透）
            String json = value == null ? "" : objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl > 0 ? ttl : ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[cache] 写入失败（不影响本次返回）。key={}, err={}", key, e.toString());
        }
        return value;
    }

    /** 前台首页聚合（TTL 30 秒 + 公告/句子写入时主动失效；文化类变化靠 TTL 兜底） */
    public <T> T getOrLoadHome(Supplier<T> loader, com.fasterxml.jackson.core.type.TypeReference<T> valueType) {
        return getOrLoad(KEY_HOME, HOME_TTL_SECONDS, loader, valueType);
    }

    /**
     * 后台菜单（按用户缓存，key 里带全局版本号，见 {@link #KEY_MENU_EPOCH}）。
     *
     * <p>菜单只影响导航展示，**真正的接口鉴权是独立的**（JWT + 各接口自己的角色判断），
     * 所以即使缓存最多滞后一个 TTL，也不会出现「菜单没了但还能调用」之外的越权问题。</p>
     */
    public <T> T getOrLoadMenu(long userId, Supplier<T> loader,
                              com.fasterxml.jackson.core.type.TypeReference<T> valueType) {
        return getOrLoad("culture:cache:menu:v" + menuEpoch() + ":user:" + userId, ttlSeconds, loader, valueType);
    }

    /** 读当前菜单缓存版本号（Redis 不可用/读取失败时返回 "0"，即退化为「不缓存」语义的直接查询） */
    public String menuEpoch() {
        if (!cacheUsable()) {
            return "0";
        }
        try {
            String v = redisTemplate.opsForValue().get(KEY_MENU_EPOCH);
            return (v == null || v.isEmpty()) ? "0" : v;
        } catch (Exception e) {
            log.warn("[cache] 读取菜单缓存版本号失败（本次按 0 处理）：{}", e.toString());
            return "0";
        }
    }

    /** 角色/权限/菜单授权发生变化时调用：版本号 +1，所有用户的菜单缓存立即失效 */
    public void bumpMenuEpoch() {
        if (!cacheUsable()) {
            return;
        }
        try {
            redisTemplate.opsForValue().increment(KEY_MENU_EPOCH);
        } catch (Exception e) {
            log.warn("[cache] 菜单缓存版本号自增失败（将由 TTL 兜底）：{}", e.toString());
        }
    }

    /** 主动失效（写操作后调用）：任意 key 删除失败都只记日志，不影响业务写入结果 */
    public void evict(String... keys) {
        if (!cacheUsable() || keys == null) {
            return;
        }
        for (String key : keys) {
            safeDelete(key);
        }
    }

    /** 分类列表失效（分类增删改/恢复后调用） */
    public void evictCategoryList() {
        evict(KEY_CATEGORY_LIST);
    }

    /** 标签列表失效（标签增删改/恢复/合并/改名、以及内容标签变化后调用） */
    public void evictTagList() {
        evict(KEY_TAG_LIST);
    }

    /** 首页聚合失效（公告 / 句子增删改后调用） */
    public void evictHome() {
        evict(KEY_HOME);
    }

    private boolean cacheUsable() {
        return enabled && redisTemplate != null;
    }

    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[cache] 删除失败（将由 TTL 兜底）。key={}, err={}", key, e.toString());
        }
    }
}
