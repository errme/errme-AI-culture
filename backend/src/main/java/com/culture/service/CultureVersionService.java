package com.culture.service;

import com.culture.entity.Culture;
import com.culture.entity.CultureVersion;
import com.culture.util.PageList;

/**
 * 内容版本历史服务（保存即留痕 / 可对比 / 可回滚）。
 *
 * <p><b>写入时机</b>：</p>
 * <ol>
 *   <li>{@link #snapshotBeforeSave}：后台修改保存（update）<b>之前</b>，把「修改前」的整条记录快照下来；
 *       新增内容不写。同一个 culture 在<b>同一自然分钟内</b>重复保存只保留一条，
 *       且<b>任何异常都被吞掉只打日志</b>，绝不影响主保存流程。</li>
 *   <li>{@link #snapshotBeforeRollback}：回滚<b>之前</b>，把「当前内容」再快照一条
 *       （<b>不做分钟去重</b>，必须写成功），这样回滚本身也是可逆的。</li>
 * </ol>
 *
 * <p><b>回滚范围</b>：只还原版本里保存的 5 个字段
 * （name / description / content / cover_url / category_id）。
 * biz_culture_version 没有 address / status / publish_at 列，因此这三项不参与回滚。</p>
 */
public interface CultureVersionService {

    /**
     * 保存前快照（update 专用）：把「修改前」的记录写入 biz_culture_version。
     *
     * <p>实现约定：</p>
     * <ul>
     *   <li>cultureId 为 null（新增）或查不到记录（已逻辑删除）时直接返回，不写快照；</li>
     *   <li>同一自然分钟内已有该 culture 的快照时跳过（避免频繁编辑刷爆版本表）；</li>
     *   <li>失败只打日志，<b>不抛出</b>，不影响主保存流程。</li>
     * </ul>
     *
     * @param cultureId    被修改内容的 id
     * @param operatorId   操作人 id（可为 null，为 null 时会尝试从登录态兜底）
     * @param operatorName 操作人用户名（可为 null，为 null 时按 operatorId 反查）
     */
    void snapshotBeforeSave(Long cultureId, Long operatorId, String operatorName);

    /**
     * 回滚前快照：把「当前内容」写入版本表，保证回滚可逆。
     * <b>不做分钟去重</b>；写入失败会抛 BusinessException 让调用方放弃本次回滚
     * （宁可回滚失败，也不能让当前内容不可恢复）。
     *
     * @param current      当前内容（调用方已查出的 biz_culture 记录，含完整正文）
     * @param operatorId   操作人 id
     * @param operatorName 操作人用户名
     */
    void snapshotBeforeRollback(Culture current, Long operatorId, String operatorName);

    /**
     * 版本分页列表（<b>不返回 content 全文</b>，只返回 contentLength）。
     *
     * @param cultureId 内容 id，必填
     * @param page      页码，从 1 开始；null/&lt;1 按 1 处理
     * @param pageSize  每页条数；null/&lt;1 按 20 处理，上限 100
     * @return {total, rows:[{id, cultureId, name, operatorName, createTime, contentLength}]}
     */
    PageList listVersions(Long cultureId, Integer page, Integer pageSize);

    /** 单条版本详情（含完整 content），用于对比/预览/回滚前确认；不存在返回 null */
    CultureVersion findVersion(Long id);

    /**
     * 回滚：把该版本的内容写回 biz_culture。<b>回滚前先写一条当前内容的快照</b>。
     *
     * @param versionId    版本 id
     * @param operatorId   操作人 id
     * @param operatorName 操作人用户名
     * @return 实际更新行数（正常为 1）
     */
    int rollback(Long versionId, Long operatorId, String operatorName);
}
