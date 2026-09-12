package com.culture.service;

import com.culture.entity.SensitiveWord;
import com.culture.util.PageList;

import java.util.List;
import java.util.Map;

/**
 * 评论敏感词服务：词表维护（后台）+ 命中判定（评论提交链路）。
 *
 * <p>判定契约：{@link #matchAction(String)} 的入参必须是<b>已清洗的纯文本</b>
 * （调用方用 {@link com.culture.util.HtmlSanitizer#toPlainText(String)} 把白名单 HTML 转纯文本，
 * 否则 {@code 广<b>告</b>} 这类插标签的写法会绕过匹配）。</p>
 *
 * <p>设计原则（与评论主流程的关系）：</p>
 * <ul>
 *   <li>命中 action=1 → 调用方抛 BusinessException，提交被拒（400）；</li>
 *   <li>命中 action=2 → 调用方照常入库但强制 status=0（待审核），并给前端温和提示；</li>
 *   <li>词表为空 / 加载异常 → 一律返回 {@link #ACTION_NONE} <b>降级放行</b>，
 *       敏感词过滤是保护措施，不能因为词表故障把评论功能整体卡死；</li>
 *   <li>启用词表带进程内缓存（默认 60 秒 TTL），后台增删改立即失效本实例缓存。</li>
 * </ul>
 */
public interface SensitiveWordService {

    /** 无命中（含降级放行） */
    int ACTION_NONE = 0;

    /** 命中 action=1：直接拒绝提交 */
    int ACTION_REJECT = 1;

    /** 命中 action=2：正常入库但强制转待审核 */
    int ACTION_PENDING = 2;

    /**
     * 对清洗后的纯文本做「大小写不敏感包含匹配」，返回 {@link #ACTION_NONE} /
     * {@link #ACTION_REJECT} / {@link #ACTION_PENDING}。
     *
     * <p>多词命中时的优先级：只要命中任意一条 action=1 的词就返回 ACTION_REJECT（拒绝优先）；
     * 否则命中 action=2 返回 ACTION_PENDING；都不命中返回 ACTION_NONE。</p>
     *
     * <p>降级：词表为空、或加载词表抛异常时返回 ACTION_NONE（不阻塞提交），只打日志。</p>
     *
     * @param plainText 清洗后的纯文本（可为 null）
     */
    int matchAction(String plainText);

    /** 后台分页：{ total, rows }（keyword 模糊匹配词本身或备注；含停用词，不含已删除词） */
    PageList page(String keyword, Integer page, Integer pageSize);

    /**
     * 新增（无 id）或更新（有 id）。
     * 词为空 / 超 100 字 / action 不是 1、2 / enabled 不是 0、1 / 词重复 → 抛 BusinessException（控制层转 400）。
     * 同名词此前被逻辑删除时「复活」旧行（uk_word 唯一键，直接插入会冲突）。
     *
     * @return { id, word, created }
     */
    Map<String, Object> save(SensitiveWord sensitiveWord);

    /** 逻辑删除单条（deleted=1） */
    void delete(Long id);

    /**
     * 批量逻辑删除。
     *
     * @param ids 词 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @return 实际删除条数
     */
    int batchDelete(List<Long> ids);

    /** 主动清空启用词表缓存（后台增删改后调用；也可给运维做手工刷新） */
    void refreshCache();
}
