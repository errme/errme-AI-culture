package com.culture.service;

import com.culture.auth.service.BusinessException;
import com.culture.entity.SysConfig;
import com.culture.mapper.SysConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务：把原先只能改 {@code application.yml} 再重启的可变项搬到数据库，
 * 后台「系统设置」页维护，改完**即时生效**。
 *
 * <h3>读取策略</h3>
 * <ol>
 *   <li>内存缓存优先（整张表只有十几行，启动时全量载入，写入后刷新）；</li>
 *   <li>缓存里没有该键 → 回退到调用方传入的默认值（即 {@code @Value} 里的配置值）；</li>
 *   <li>因此「sys_config 表不存在 / 少了某一行 / 数据库暂时不可用」都<b>不会导致服务不可用</b>，
 *       只会退回改造前的行为 —— 这一点是刻意的：配置中心本身不能成为新的故障点。</li>
 * </ol>
 *
 * <h3>校验策略（规则也在数据里，不写死）</h3>
 * <ul>
 *   <li>{@code value_type=int}：必须能解析为整数，且落在该行的
 *       {@code min_value}~{@code max_value} 之间（范围来自数据库，可随时调整）；</li>
 *   <li>{@code value_type=bool}：只接受 true/false（大小写不敏感）；</li>
 *   <li>{@code string}：仅做长度与空值校验；对 {@code site.base-url} 额外要求以 http(s):// 开头
 *       且不以 / 结尾 —— 它会被拼进 sitemap/rss 的绝对地址，格式错了影响面很大。</li>
 * </ul>
 *
 * <h3>与启动期配置的边界</h3>
 * <p>端口、数据源、Redis、上传目录、Druid 监控页与 API 文档的开关/路径等，
 * 在容器启动阶段就要用到（Druid 还依赖数据源本身），放进本表会形成循环依赖，
 * 因此仍由 {@code application.yml} + 环境变量管理。</p>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** 单个字符串值的长度上限，与 sys_config.config_value 的列宽一致 */
    private static final int MAX_VALUE_LENGTH = 1000;

    private final SysConfigMapper sysConfigMapper;

    /** 内存缓存：key → value。整体替换，读多写极少，用 volatile + 不可变 Map 保证可见性 */
    private volatile Map<String, String> valueCache = Collections.emptyMap();

    /** 元信息缓存：key → 配置行（含 label/type/min/max），供后台列表与校验使用 */
    private volatile List<SysConfig> allCache = Collections.emptyList();

    /** @param sysConfigMapper 允许为 null（单测/自测场景），此时所有读取走默认值 */
    public ConfigService(SysConfigMapper sysConfigMapper) {
        this.sysConfigMapper = sysConfigMapper;
    }

    /** 启动时载入；任何异常都只记日志，绝不让应用起不来 */
    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新从数据库载入配置（写入后调用） */
    public void refresh() {
        if (sysConfigMapper == null) {
            // 无 Mapper 的场景（单元测试 / 离线自测）：直接保持空缓存，
            // 所有读取都会回退到调用方传入的默认值
            return;
        }
        try {
            List<SysConfig> rows = sysConfigMapper.findAll();
            if (rows == null) rows = Collections.emptyList();
            Map<String, String> map = new HashMap<>(rows.size() * 2);
            for (SysConfig r : rows) {
                if (r != null && r.getConfigKey() != null) {
                    map.put(r.getConfigKey(), r.getConfigValue());
                }
            }
            this.valueCache = map;
            this.allCache = rows;
            log.info("[config] 已载入 {} 项系统配置", rows.size());
        } catch (Exception e) {
            // 典型场景：还没执行 docs/sql/13_sys_config.sql（表不存在）。
            // 此时保持空缓存，所有读取都会回退到 application.yml 的默认值，服务照常可用。
            log.warn("[config] 载入系统配置失败，将回退到 application.yml 默认值：{}", e.getMessage());
        }
    }

    // ==================== 读取 ====================

    /** 取字符串值；未配置时返回 fallback */
    public String getString(String key, String fallback) {
        String v = valueCache.get(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /**
     * 取整数值；未配置或解析失败时返回 fallback。
     * 解析失败不抛异常 —— 读取路径不能因为一个坏值就 500。
     */
    public int getInt(String key, int fallback) {
        String v = valueCache.get(key);
        if (v == null || v.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("[config] {} 的值 '{}' 不是合法整数，回退为 {}", key, v, fallback);
            return fallback;
        }
    }

    /** 取布尔值；未配置时返回 fallback */
    public boolean getBool(String key, boolean fallback) {
        String v = valueCache.get(key);
        if (v == null || v.trim().isEmpty()) return fallback;
        String s = v.trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        log.warn("[config] {} 的值 '{}' 不是合法布尔，回退为 {}", key, v, fallback);
        return fallback;
    }

    /** 后台列表用：全部配置（含元信息），按分组与排序 */
    public List<SysConfig> list() {
        return allCache;
    }

    // ==================== 写入 ====================

    /**
     * 批量更新配置。
     *
     * <p>先整体校验再落库：任何一个值不合法就整批拒绝，避免出现「改了一半」的中间状态。
     * 校验规则来自该行自身的 value_type 与 min/max（见类注释）。</p>
     *
     * @param entries    key → value
     * @param operatorId 操作人（写入 updated_by）
     * @return 实际更新条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int update(Map<String, String> entries, Long operatorId) {
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("没有需要保存的配置项");
        }

        // 元信息索引（label/type/min/max 都在这里）
        Map<String, SysConfig> meta = new LinkedHashMap<>();
        for (SysConfig c : allCache) {
            meta.put(c.getConfigKey(), c);
        }

        // ---- 1) 先整体校验 ----
        List<String> errors = new ArrayList<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String raw = e.getValue();
            SysConfig def = meta.get(key);
            if (def == null) {
                errors.add("未知配置项：" + key);
                continue;
            }
            String value = raw == null ? "" : raw.trim();
            String err = validate(def, value);
            if (err != null) {
                errors.add(def.getLabel() + "：" + err);
                continue;
            }
            normalized.put(key, value);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException("配置校验未通过 —— " + String.join("；", errors));
        }

        // ---- 2) 再统一落库 ----
        if (sysConfigMapper == null) {
            throw new BusinessException("当前环境不支持写入系统配置（未接数据库）");
        }
        int affected = 0;
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            affected += sysConfigMapper.updateValue(e.getKey(), e.getValue(), operatorId);
        }

        // ---- 3) 刷新缓存，让改动即时生效 ----
        refresh();
        log.info("[config] 管理员 {} 更新了 {} 项系统配置", operatorId, affected);
        return affected;
    }

    /**
     * 把某一项恢复为出厂默认值（default_value）。
     *
     * @return 实际更新条数（0 表示该键不存在或没有默认值）
     */
    @Transactional(rollbackFor = Exception.class)
    public int resetToDefault(String key, Long operatorId) {
        if (key == null || key.trim().isEmpty()) {
            throw new BusinessException("缺少配置键");
        }
        for (SysConfig c : allCache) {
            if (key.equals(c.getConfigKey())) {
                if (c.getDefaultValue() == null) {
                    throw new BusinessException("该配置项没有出厂默认值，无法恢复");
                }
                Map<String, String> one = new LinkedHashMap<>();
                one.put(key, c.getDefaultValue());
                return update(one, operatorId);
            }
        }
        throw new BusinessException("未知配置项：" + key);
    }

    // ==================== 校验 ====================

    /** @return null=通过；否则返回错误说明 */
    private String validate(SysConfig def, String value) {
        if (value.length() > MAX_VALUE_LENGTH) {
            return "长度超过 " + MAX_VALUE_LENGTH + " 个字符";
        }
        String type = def.getValueType() == null ? "string" : def.getValueType().trim().toLowerCase();

        if ("int".equals(type)) {
            if (value.isEmpty()) return "不能为空";
            long n;
            try {
                n = Long.parseLong(value);
            } catch (NumberFormatException e) {
                return "必须是整数";
            }
            if (def.getMinValue() != null && n < def.getMinValue()) {
                return "不能小于 " + def.getMinValue();
            }
            if (def.getMaxValue() != null && n > def.getMaxValue()) {
                return "不能大于 " + def.getMaxValue();
            }
            return null;
        }

        if ("bool".equals(type)) {
            String s = value.toLowerCase();
            if (!"true".equals(s) && !"false".equals(s)) {
                return "只能是 true 或 false";
            }
            return null;
        }

        // ---- string ----
        if (value.isEmpty() && "site.name".equals(def.getConfigKey())) {
            return "不能为空";
        }
        if ("site.base-url".equals(def.getConfigKey())) {
            // 这个值会拼进 sitemap / rss / og:image 的绝对地址，格式错影响面很大，单独校验
            String s = value.toLowerCase();
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                return "必须以 http:// 或 https:// 开头";
            }
            if (value.endsWith("/")) {
                return "结尾不要带斜杠（拼接时会产生 // ）";
            }
        }
        return null;
    }

    // ==================== 调试/测试辅助 ====================

    /** 当前缓存快照（只读），供诊断接口或单测使用 */
    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(valueCache));
    }
}
