package com.evops.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 入口过滤器：接力或生成 traceId，写入 MDC 与响应头，请求结束清理。
 * 注册为最高优先级，保证 Shiro、业务、持久层日志都落在同一 traceId 下。
 */
public class TraceIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceContext.sanitize(request.getHeader(TraceContext.TRACE_HEADER));
        if (traceId == null) {
            traceId = TraceContext.newTraceId();
        }
        MDC.put(TraceContext.TRACE_ID_KEY, traceId);
        // 响应头回带：调用方/网关可凭此串起上下游
        response.setHeader(TraceContext.TRACE_HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("HTTP {} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), System.currentTimeMillis() - start);
            // 线程归还池前必须清理，否则下个请求会串号
            MDC.clear();
        }
    }
}
