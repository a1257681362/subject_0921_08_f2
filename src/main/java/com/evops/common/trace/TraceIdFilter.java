package com.evops.common.trace;

import com.evops.system.TraceRecord;
import com.evops.system.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 链路入口：生成/透传 TraceId 并回带响应头，请求结束异步落调用轨迹。
 * 排在最前，保证 Shiro 等后续过滤器拒绝时也有 TraceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[0-9a-zA-Z-]{8,64}");
    private static final int ERROR_MSG_MAX = 500;

    private final TraceService traceService;

    public TraceIdFilter(TraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.TRACE_HEADER);
        if (!StringUtils.hasText(traceId) || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.init(traceId);
        response.setHeader(TraceContext.TRACE_HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long costMs = System.currentTimeMillis() - start;
            if (!isSkipped(request.getRequestURI())) {
                log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), costMs);
                traceService.recordAsync(buildRecord(request, response.getStatus(), traceId, costMs));
            }
            TraceContext.clear();
        }
    }

    private TraceRecord buildRecord(HttpServletRequest request, int status, String traceId, long costMs) {
        String errorMsg = TraceContext.errorMsg();
        if (errorMsg != null && errorMsg.length() > ERROR_MSG_MAX) {
            errorMsg = errorMsg.substring(0, ERROR_MSG_MAX);
        }
        TraceRecord record = new TraceRecord();
        record.setTraceId(traceId);
        record.setOrderNo(TraceContext.orderNo());
        record.setHttpMethod(request.getMethod());
        record.setUri(request.getRequestURI());
        record.setHttpStatus(status);
        record.setSuccess(errorMsg == null && status < 500);
        record.setCostMs(costMs);
        record.setErrorMsg(errorMsg);
        return record;
    }

    /** 健康检查等高频探活不落轨迹，避免淹没主链路 */
    private boolean isSkipped(String uri) {
        return uri != null && uri.startsWith("/api/health");
    }
}
