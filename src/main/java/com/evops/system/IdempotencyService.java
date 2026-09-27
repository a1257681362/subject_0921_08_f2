package com.evops.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * 幂等执行器：新增与变更请求必须携带请求号。
 * <p>
 * 业务写入与请求台账在同一事务内提交——要么一起落库，要么一起回滚，
 * 保证"双击保存只生成一条记录，重复请求返回首次结果"。
 * 并发重复时唯一约束兜底：本事务回滚后等待首次请求提交，返回其结果。
 */
@Service
public class IdempotencyService {
    private final RequestRecordMapper requestRecordMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyService(RequestRecordMapper requestRecordMapper,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.requestRecordMapper = requestRecordMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * @param requestNo 请求号（必填，幂等键）
     * @param bizType   业务类型，仅用于台账排查
     * @param type      响应类型，用于重复请求时还原首次结果
     * @param action    业务动作，在事务内执行
     */
    public <T> T run(String requestNo, String bizType, Class<T> type, Supplier<T> action) {
        if (!StringUtils.hasText(requestNo)) {
            throw new IllegalArgumentException("请求号不能为空");
        }
        RequestRecord hit = findByRequestNo(requestNo);
        if (hit != null) {
            return decode(hit.getResponseJson(), type);
        }
        try {
            return transactionTemplate.execute(status -> {
                T result = action.get();
                RequestRecord record = new RequestRecord();
                record.setRequestNo(requestNo);
                record.setBizType(bizType);
                record.setResponseJson(encode(result));
                requestRecordMapper.insert(record);
                return result;
            });
        } catch (DuplicateKeyException e) {
            // 并发重复提交：本事务已回滚，等待首次请求提交后返回首次结果
            for (int i = 0; i < 20; i++) {
                RequestRecord again = findByRequestNo(requestNo);
                if (again != null) {
                    return decode(again.getResponseJson(), type);
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new IllegalStateException("相同请求正在处理中，请稍后重试");
        }
    }

    private RequestRecord findByRequestNo(String requestNo) {
        return requestRecordMapper.selectOne(Wrappers.<RequestRecord>lambdaQuery()
                .eq(RequestRecord::getRequestNo, requestNo));
    }

    private String encode(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败");
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("首次结果还原失败");
        }
    }
}
