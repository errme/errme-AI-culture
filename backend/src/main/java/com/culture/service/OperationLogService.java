package com.culture.service;

import com.culture.entity.OperationLog;
import com.culture.query.OperationLogQuery;
import com.culture.util.PageList;

/**
 * 操作日志（审计）服务。
 * 约定：记录失败绝不影响主流程，调用方（拦截器）已做兜底 try/catch。
 */
public interface OperationLogService {

    /** 写入一条日志 */
    void record(OperationLog log);

    /**
     * 便捷写入。
     *
     * @param success 1 成功 0 失败
     */
    void record(Long userId, String username, String module, String action, String targetId,
                String detail, String method, String uri, String ip, Integer success, Long costMs);

    /** 后台分页查询（module + keyword） */
    PageList page(OperationLogQuery query);
}
