package com.evops.common.trace;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路追踪上下文：traceId 从入口到持久层一路随 MDC 携带；
 * 工单号绑定后，本请求（含异步接力）所有日志都可按工单号检索。
 */
public final class TraceContext {
    /** 请求/响应头名：上下游以此接力同一个 traceId */
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** MDC key，日志格式中用 %X{traceId} 输出 */
    public static final String TRACE_ID_KEY = "traceId";
    /** MDC key，日志格式中用 %X{orderNo} 输出 */
    public static final String ORDER_NO_KEY = "orderNo";

    /** 外部传入 traceId 只允许安全字符，防日志注入伪造 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /** 生成新 traceId：32 位小写十六进制、无分隔符，方便 grep */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 校验外部传入的 traceId，非法一律视为未携带（返回 null） */
    public static String sanitize(String incoming) {
        if (StringUtils.hasText(incoming) && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return null;
    }

    /** 绑定工单号：绑定后本线程及异步接力线程的日志都带 orderNo */
    public static void bindOrderNo(String orderNo) {
        if (StringUtils.hasText(orderNo)) {
            MDC.put(ORDER_NO_KEY, orderNo);
        }
    }
}
