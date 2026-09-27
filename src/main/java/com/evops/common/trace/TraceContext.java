package com.evops.common.trace;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

/**
 * 链路追踪上下文：TraceId 从入口到持久层一路携带，异步任务靠 {@link #wrap} 接力。
 * <p>
 * traceId / orderNo / errorMsg 均放在 MDC：日志格式只输出 traceId，
 * orderNo 供落库与按工单号检索使用，errorMsg 由全局异常处理标记业务失败。
 */
public final class TraceContext {
    /** 请求头 / 响应头名 */
    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_ID = "traceId";
    public static final String ORDER_NO = "orderNo";
    public static final String ERROR_MSG = "errorMsg";

    private TraceContext() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void init(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static String traceId() {
        return MDC.get(TRACE_ID);
    }

    /** 绑定当前请求涉及的工单号，供调用轨迹按工单号检索 */
    public static void bindOrderNo(String orderNo) {
        if (StringUtils.hasText(orderNo)) {
            MDC.put(ORDER_NO, orderNo);
        }
    }

    public static String orderNo() {
        return MDC.get(ORDER_NO);
    }

    /** 标记本次调用业务失败（异常出口统一调用），调用轨迹据此记成败 */
    public static void markFailed(String message) {
        MDC.put(ERROR_MSG, message);
    }

    public static String errorMsg() {
        return MDC.get(ERROR_MSG);
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(ORDER_NO);
        MDC.remove(ERROR_MSG);
    }

    /**
     * 异步接力：捕获提交时刻的上下文快照，在异步线程内恢复，执行完清理，
     * 避免污染线程池里复用的线程。
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (snapshot != null) {
                MDC.setContextMap(snapshot);
            } else {
                clear();
            }
            try {
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    clear();
                }
            }
        };
    }
}
