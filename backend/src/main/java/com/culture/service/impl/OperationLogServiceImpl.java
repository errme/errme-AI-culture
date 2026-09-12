package com.culture.service.impl;

import com.culture.entity.OperationLog;
import com.culture.mapper.OperationLogMapper;
import com.culture.query.OperationLogQuery;
import com.culture.service.OperationLogService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务实现。
 */
@Service
public class OperationLogServiceImpl implements OperationLogService {

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Override
    public void record(OperationLog log) {
        if (log == null) return;
        operationLogMapper.insert(log);
    }

    @Override
    public void record(Long userId, String username, String module, String action, String targetId,
                       String detail, String method, String uri, String ip, Integer success, Long costMs) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setModule(module);
        log.setAction(action);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setMethod(method);
        log.setUri(uri);
        log.setIp(ip);
        log.setSuccess(success == null ? 1 : success);
        log.setCostMs(costMs);
        operationLogMapper.insert(log);
    }

    @Override
    public PageList page(OperationLogQuery query) {
        if (query == null) query = new OperationLogQuery();
        query.normalizePaging();
        if (query.getModule() != null) {
            String m = query.getModule().trim();
            query.setModule(m.isEmpty() ? null : m);
        }
        if (query.getKeyword() != null) {
            String kw = query.getKeyword().trim();
            query.setKeyword(kw.isEmpty() ? null : kw);
        }

        PageList pageList = new PageList();
        Long total = operationLogMapper.queryTotal(query);
        pageList.setTotal(total == null ? 0L : total);
        pageList.setRows(operationLogMapper.queryData(query));
        return pageList;
    }
}
