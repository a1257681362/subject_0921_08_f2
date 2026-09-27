package com.evops.system;

import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链路追踪查询：定位线上故障时，先按工单号拉出完整调用时序，
 * 再拿每行的 traceId 回查日志（日志每行都带 traceId）。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {
    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    /** 按工单号检索完整调用时序 */
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<List<TraceRecord>> timeline(@PathVariable String orderNo) {
        return ApiResponse.ok(traceService.timelineByOrderNo(orderNo));
    }
}
