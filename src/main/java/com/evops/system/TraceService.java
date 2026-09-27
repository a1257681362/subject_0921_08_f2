package com.evops.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 调用轨迹：异步落库（TraceContext 由线程池装饰器接力），按工单号检索完整调用时序。
 */
@Service
public class TraceService {
    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    private final TraceRecordMapper traceRecordMapper;
    private final ThreadPoolTaskExecutor traceTaskExecutor;

    public TraceService(TraceRecordMapper traceRecordMapper,
                        @Qualifier("traceTaskExecutor") ThreadPoolTaskExecutor traceTaskExecutor) {
        this.traceRecordMapper = traceRecordMapper;
        this.traceTaskExecutor = traceTaskExecutor;
    }

    /**
     * 异步落调用轨迹：落库失败只记日志，绝不影响主链路。
     */
    public void recordAsync(TraceRecord record) {
        try {
            traceTaskExecutor.execute(() -> {
                try {
                    traceRecordMapper.insert(record);
                } catch (Exception e) {
                    log.warn("调用轨迹落库失败：{}", e.toString());
                }
            });
        } catch (RuntimeException e) {
            log.warn("调用轨迹任务提交失败：{}", e.toString());
        }
    }

    /** 按工单号检索完整调用时序（按发生先后排序） */
    public List<TraceRecord> timelineByOrderNo(String orderNo) {
        return traceRecordMapper.selectList(Wrappers.<TraceRecord>lambdaQuery()
                .eq(TraceRecord::getOrderNo, orderNo)
                .orderByAsc(TraceRecord::getId));
    }
}
