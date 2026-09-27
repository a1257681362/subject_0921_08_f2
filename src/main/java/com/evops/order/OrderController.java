package com.evops.order;

import com.evops.common.ApiResponse;
import com.evops.common.VoidRequest;
import com.evops.order.dto.AssignRequest;
import com.evops.order.dto.OrderSaveRequest;
import com.evops.order.dto.OrderVO;
import com.evops.order.dto.RescheduleRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ApiResponse<OrderVO> create(@Validated @RequestBody OrderSaveRequest req) {
        return ApiResponse.ok(orderService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<OrderVO> update(@PathVariable Long id, @Validated @RequestBody OrderSaveRequest req) {
        return ApiResponse.ok(orderService.update(id, req));
    }

    @PostMapping("/{id}/void")
    public ApiResponse<OrderVO> voidOrder(@PathVariable Long id, @Validated @RequestBody VoidRequest req) {
        return ApiResponse.ok(orderService.voidOrder(id, req));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<OrderVO> restore(@PathVariable Long id, @Validated @RequestBody VoidRequest req) {
        return ApiResponse.ok(orderService.restore(id, req));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<OrderVO> assign(@PathVariable Long id, @Validated @RequestBody AssignRequest req) {
        return ApiResponse.ok(orderService.assign(id, req));
    }

    @PostMapping("/{id}/reschedule")
    public ApiResponse<OrderVO> reschedule(@PathVariable Long id, @Validated @RequestBody RescheduleRequest req) {
        return ApiResponse.ok(orderService.reschedule(id, req));
    }

    /** 组合检索：工单号 + 服务日期，至少提供一个 */
    @GetMapping("/search")
    public ApiResponse<List<OrderVO>> search(@RequestParam(required = false) String orderNo,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(defaultValue = "false") boolean includeVoided) {
        return ApiResponse.ok(orderService.search(orderNo, date, includeVoided));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderVO> get(@PathVariable Long id) {
        return ApiResponse.ok(orderService.get(id));
    }

    @GetMapping
    public ApiResponse<List<OrderVO>> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) String status) {
        return ApiResponse.ok(orderService.list(date, status));
    }
}
