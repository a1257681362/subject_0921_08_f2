package com.evops.system;

import org.springframework.stereotype.Component;

/**
 * 操作留痕：在业务事务内调用，随业务同生共死。
 */
@Component
public class OperationLogger {
    private final OperationLogMapper operationLogMapper;

    public OperationLogger(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    public void log(String bizType, Long bizId, String action, String requestNo, String detail) {
        OperationLog log = new OperationLog();
        log.setBizType(bizType);
        log.setBizId(bizId);
        log.setAction(action);
        log.setRequestNo(requestNo);
        log.setDetail(detail);
        operationLogMapper.insert(log);
    }
}
