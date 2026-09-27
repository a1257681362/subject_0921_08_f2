package com.evops.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.evops.common.BizConsts;
import com.evops.common.VoidRequest;
import com.evops.common.trace.TraceContext;
import com.evops.order.dto.AssignRequest;
import com.evops.order.dto.OrderSaveRequest;
import com.evops.order.dto.OrderVO;
import com.evops.order.dto.RescheduleRequest;
import com.evops.staff.Staff;
import com.evops.staff.StaffLeave;
import com.evops.staff.StaffLeaveMapper;
import com.evops.staff.StaffMapper;
import com.evops.system.IdempotencyService;
import com.evops.system.OperationLogger;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务单：主表与明细同生共死（同一事务），物理删除一律禁止。
 * 派单避开人员休假与既有排班；改期冲突时自动顺延。
 */
@Service
public class OrderService {
    /** 改期自动顺延的最长查找天数 */
    private static final int MAX_POSTPONE_DAYS = 30;

    private final ServiceOrderMapper orderMapper;
    private final ServiceOrderItemMapper itemMapper;
    private final StaffMapper staffMapper;
    private final StaffLeaveMapper leaveMapper;
    private final IdempotencyService idempotencyService;
    private final OperationLogger operationLogger;

    public OrderService(ServiceOrderMapper orderMapper,
                        ServiceOrderItemMapper itemMapper,
                        StaffMapper staffMapper,
                        StaffLeaveMapper leaveMapper,
                        IdempotencyService idempotencyService,
                        OperationLogger operationLogger) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.staffMapper = staffMapper;
        this.leaveMapper = leaveMapper;
        this.idempotencyService = idempotencyService;
        this.operationLogger = operationLogger;
    }

    public OrderVO create(OrderSaveRequest req) {
        TraceContext.bindOrderNo(req.getOrderNo());
        return idempotencyService.run(req.getRequestNo(), "ORDER_CREATE", OrderVO.class, () -> {
            Long exists = orderMapper.selectCount(Wrappers.<ServiceOrder>lambdaQuery()
                    .eq(ServiceOrder::getOrderNo, req.getOrderNo()));
            if (exists != null && exists > 0) {
                throw new IllegalArgumentException("工单号已存在：" + req.getOrderNo());
            }
            ServiceOrder order = new ServiceOrder();
            BeanUtils.copyProperties(req, order);
            order.setStatus(BizConsts.ORDER_PENDING);
            order.setRecordStatus(BizConsts.RECORD_NORMAL);
            order.setVersion(0);
            order.setLastRequestNo(req.getRequestNo());
            orderMapper.insert(order);
            insertItems(order.getId(), req.getItems());
            operationLogger.log("ORDER", order.getId(), "CREATE", req.getRequestNo(),
                    "新增服务单 " + order.getOrderNo());
            return toVO(order);
        });
    }

    public OrderVO update(Long id, OrderSaveRequest req) {
        TraceContext.bindOrderNo(req.getOrderNo());
        return idempotencyService.run(req.getRequestNo(), "ORDER_UPDATE", OrderVO.class, () -> {
            ServiceOrder order = mustGetNormal(id);
            if (!BizConsts.ORDER_PENDING.equals(order.getStatus())) {
                throw new IllegalArgumentException("仅待派单状态可修改，已派单请走改期");
            }
            checkVersion(req.getVersion(), order.getVersion());
            if (!order.getOrderNo().equals(req.getOrderNo())) {
                throw new IllegalArgumentException("工单号不允许修改");
            }
            order.setCustomerName(req.getCustomerName());
            order.setCustomerPhone(req.getCustomerPhone());
            order.setAddress(req.getAddress());
            order.setServiceDate(req.getServiceDate());
            order.setTimeSlot(req.getTimeSlot());
            order.setLastRequestNo(req.getRequestNo());
            if (orderMapper.updateById(order) == 0) {
                throw new IllegalArgumentException("记录已被他人变更，请刷新后重试");
            }
            // 明细随主单同生共死：旧明细冲销（REPLACE），新明细写入，同一事务
            voidItems(id, BizConsts.VOID_BY_REPLACE);
            insertItems(id, req.getItems());
            operationLogger.log("ORDER", order.getId(), "UPDATE", req.getRequestNo(),
                    "变更服务单 " + order.getOrderNo());
            return toVO(order);
        });
    }

    public OrderVO voidOrder(Long id, VoidRequest req) {
        return idempotencyService.run(req.getRequestNo(), "ORDER_VOID", OrderVO.class, () -> {
            ServiceOrder order = mustGetNormal(id);
            TraceContext.bindOrderNo(order.getOrderNo());
            order.setRecordStatus(BizConsts.RECORD_VOIDED);
            order.setLastRequestNo(req.getRequestNo());
            orderMapper.updateById(order);
            voidItems(id, BizConsts.VOID_BY_ORDER);
            operationLogger.log("ORDER", order.getId(), "VOID", req.getRequestNo(),
                    "冲销服务单 " + order.getOrderNo());
            return toVO(order);
        });
    }

    public OrderVO restore(Long id, VoidRequest req) {
        return idempotencyService.run(req.getRequestNo(), "ORDER_RESTORE", OrderVO.class, () -> {
            ServiceOrder order = mustGet(id);
            TraceContext.bindOrderNo(order.getOrderNo());
            if (!BizConsts.RECORD_VOIDED.equals(order.getRecordStatus())) {
                throw new IllegalArgumentException("单据未冲销，无需恢复");
            }
            order.setRecordStatus(BizConsts.RECORD_NORMAL);
            order.setLastRequestNo(req.getRequestNo());
            orderMapper.updateById(order);
            // 仅恢复随主单冲销的明细；变更替换掉的明细保持冲销
            itemMapper.update(null, Wrappers.<ServiceOrderItem>lambdaUpdate()
                    .eq(ServiceOrderItem::getOrderId, id)
                    .eq(ServiceOrderItem::getVoidCause, BizConsts.VOID_BY_ORDER)
                    .set(ServiceOrderItem::getRecordStatus, BizConsts.RECORD_NORMAL)
                    .set(ServiceOrderItem::getVoidCause, null));
            operationLogger.log("ORDER", order.getId(), "RESTORE", req.getRequestNo(),
                    "恢复服务单 " + order.getOrderNo());
            return toVO(order);
        });
    }

    /**
     * 派单：避开人员休假与既有排班，冲突即拒绝。
     */
    public OrderVO assign(Long id, AssignRequest req) {
        return idempotencyService.run(req.getRequestNo(), "ORDER_ASSIGN", OrderVO.class, () -> {
            ServiceOrder order = mustGetNormal(id);
            TraceContext.bindOrderNo(order.getOrderNo());
            if (!BizConsts.ORDER_PENDING.equals(order.getStatus())) {
                throw new IllegalArgumentException("仅待派单状态可派单");
            }
            Staff staff = mustGetAssignableStaff(req.getStaffId());
            checkStaffAvailable(staff.getId(), order.getServiceDate(), order.getTimeSlot(), order.getId());
            order.setStaffId(staff.getId());
            order.setStatus(BizConsts.ORDER_ASSIGNED);
            order.setLastRequestNo(req.getRequestNo());
            orderMapper.updateById(order);
            operationLogger.log("ORDER", order.getId(), "ASSIGN", req.getRequestNo(),
                    "派单给 " + staff.getName() + "，日期 " + order.getServiceDate() + " " + order.getTimeSlot());
            return toVO(order);
        });
    }

    /**
     * 改期：已派单的工单若目标日期与人员休假/排班冲突，自动顺延到最近可用日期。
     */
    public OrderVO reschedule(Long id, RescheduleRequest req) {
        return idempotencyService.run(req.getRequestNo(), "ORDER_RESCHEDULE", OrderVO.class, () -> {
            ServiceOrder order = mustGetNormal(id);
            TraceContext.bindOrderNo(order.getOrderNo());
            LocalDate from = order.getServiceDate();
            LocalDate actual = req.getServiceDate();
            String detail;
            if (order.getStaffId() != null) {
                actual = firstAvailableDate(order.getStaffId(), req.getServiceDate(), order.getTimeSlot(), order.getId());
                detail = actual.equals(req.getServiceDate())
                        ? "改期 " + from + " → " + actual
                        : "改期 " + from + " → " + actual + "（目标日期 " + req.getServiceDate() + " 冲突，自动顺延）";
            } else {
                detail = "改期 " + from + " → " + actual;
            }
            order.setServiceDate(actual);
            order.setLastRequestNo(req.getRequestNo());
            orderMapper.updateById(order);
            operationLogger.log("ORDER", order.getId(), "RESCHEDULE", req.getRequestNo(), detail);
            return toVO(order);
        });
    }

    /**
     * 组合检索：工单号 + 服务日期，至少提供一个条件。
     */
    public List<OrderVO> search(String orderNo, LocalDate date, boolean includeVoided) {
        if (!StringUtils.hasText(orderNo) && date == null) {
            throw new IllegalArgumentException("工单号与日期至少提供一个");
        }
        TraceContext.bindOrderNo(orderNo);
        List<ServiceOrder> orders = orderMapper.selectList(Wrappers.<ServiceOrder>lambdaQuery()
                .eq(StringUtils.hasText(orderNo), ServiceOrder::getOrderNo, orderNo)
                .eq(date != null, ServiceOrder::getServiceDate, date)
                .eq(!includeVoided, ServiceOrder::getRecordStatus, BizConsts.RECORD_NORMAL)
                .orderByDesc(ServiceOrder::getId));
        return orders.stream().map(this::toVO).collect(Collectors.toList());
    }

    public OrderVO get(Long id) {
        ServiceOrder order = mustGet(id);
        TraceContext.bindOrderNo(order.getOrderNo());
        return toVO(order);
    }

    public List<OrderVO> list(LocalDate date, String status) {
        List<ServiceOrder> orders = orderMapper.selectList(Wrappers.<ServiceOrder>lambdaQuery()
                .eq(date != null, ServiceOrder::getServiceDate, date)
                .eq(StringUtils.hasText(status), ServiceOrder::getStatus, status)
                .eq(ServiceOrder::getRecordStatus, BizConsts.RECORD_NORMAL)
                .orderByDesc(ServiceOrder::getId));
        return orders.stream().map(this::toVO).collect(Collectors.toList());
    }

    // ---------- 内部 ----------

    private void insertItems(Long orderId, List<OrderSaveRequest.Item> items) {
        for (OrderSaveRequest.Item req : items) {
            ServiceOrderItem item = new ServiceOrderItem();
            item.setOrderId(orderId);
            item.setItemName(req.getItemName());
            item.setQuantity(req.getQuantity());
            item.setPrice(req.getPrice());
            item.setRecordStatus(BizConsts.RECORD_NORMAL);
            itemMapper.insert(item);
        }
    }

    private void voidItems(Long orderId, String voidCause) {
        itemMapper.update(null, Wrappers.<ServiceOrderItem>lambdaUpdate()
                .eq(ServiceOrderItem::getOrderId, orderId)
                .eq(ServiceOrderItem::getRecordStatus, BizConsts.RECORD_NORMAL)
                .set(ServiceOrderItem::getRecordStatus, BizConsts.RECORD_VOIDED)
                .set(ServiceOrderItem::getVoidCause, voidCause));
    }

    private Staff mustGetAssignableStaff(Long staffId) {
        Staff staff = staffMapper.selectById(staffId);
        if (staff == null || !BizConsts.RECORD_NORMAL.equals(staff.getRecordStatus())) {
            throw new IllegalArgumentException("服务人员不存在或已冲销：" + staffId);
        }
        if (!BizConsts.STAFF_ACTIVE.equals(staff.getStatus())) {
            throw new IllegalArgumentException("服务人员已停用：" + staff.getName());
        }
        return staff;
    }

    /** 校验人员当日可排：未休假且同时段无既有排班，冲突即抛错 */
    private void checkStaffAvailable(Long staffId, LocalDate date, String timeSlot, Long excludeOrderId) {
        Long onLeave = leaveMapper.selectCount(Wrappers.<StaffLeave>lambdaQuery()
                .eq(StaffLeave::getStaffId, staffId)
                .eq(StaffLeave::getLeaveDate, date)
                .eq(StaffLeave::getRecordStatus, BizConsts.RECORD_NORMAL));
        if (onLeave != null && onLeave > 0) {
            throw new IllegalArgumentException("该人员 " + date + " 休假，不可派单");
        }
        List<ServiceOrder> scheduled = orderMapper.selectList(Wrappers.<ServiceOrder>lambdaQuery()
                .eq(ServiceOrder::getStaffId, staffId)
                .eq(ServiceOrder::getServiceDate, date)
                .eq(ServiceOrder::getRecordStatus, BizConsts.RECORD_NORMAL)
                .eq(ServiceOrder::getStatus, BizConsts.ORDER_ASSIGNED)
                .ne(excludeOrderId != null, ServiceOrder::getId, excludeOrderId));
        for (ServiceOrder existing : scheduled) {
            if (slotConflict(existing.getTimeSlot(), timeSlot)) {
                throw new IllegalArgumentException("该人员 " + date + " " + timeSlot + " 已有排班：" + existing.getOrderNo());
            }
        }
    }

    private boolean isStaffAvailable(Long staffId, LocalDate date, String timeSlot, Long excludeOrderId) {
        try {
            checkStaffAvailable(staffId, date, timeSlot, excludeOrderId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 从目标日期起找最近可排日期（自动顺延），超过上限抛错 */
    private LocalDate firstAvailableDate(Long staffId, LocalDate target, String timeSlot, Long excludeOrderId) {
        LocalDate candidate = target;
        for (int i = 0; i <= MAX_POSTPONE_DAYS; i++) {
            if (isStaffAvailable(staffId, candidate, timeSlot, excludeOrderId)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        throw new IllegalArgumentException("目标日期起 " + MAX_POSTPONE_DAYS + " 天内无可顺延日期");
    }

    /** 时段冲突：全天与任一时段冲突，相同时段冲突 */
    private boolean slotConflict(String a, String b) {
        if (BizConsts.SLOT_ALLDAY.equals(a) || BizConsts.SLOT_ALLDAY.equals(b)) {
            return true;
        }
        return a.equals(b);
    }

    private ServiceOrder mustGet(Long id) {
        ServiceOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("服务单不存在：" + id);
        }
        return order;
    }

    private ServiceOrder mustGetNormal(Long id) {
        ServiceOrder order = mustGet(id);
        if (!BizConsts.RECORD_NORMAL.equals(order.getRecordStatus())) {
            throw new IllegalArgumentException("服务单已冲销：" + id);
        }
        return order;
    }

    private void checkVersion(Integer requestVersion, Integer currentVersion) {
        if (requestVersion == null) {
            throw new IllegalArgumentException("变更请求必须携带版本号");
        }
        if (!requestVersion.equals(currentVersion)) {
            throw new IllegalArgumentException("记录已被他人变更，请刷新后重试");
        }
    }

    private OrderVO toVO(ServiceOrder order) {
        OrderVO vo = new OrderVO();
        BeanUtils.copyProperties(order, vo);
        if (order.getStaffId() != null) {
            Staff staff = staffMapper.selectById(order.getStaffId());
            if (staff != null) {
                vo.setStaffName(staff.getName());
            }
        }
        List<ServiceOrderItem> items;
        if (BizConsts.RECORD_VOIDED.equals(order.getRecordStatus())) {
            // 已冲销单据展示冲销时点生效的明细（随主单冲销的那批）
            items = itemMapper.selectList(Wrappers.<ServiceOrderItem>lambdaQuery()
                    .eq(ServiceOrderItem::getOrderId, order.getId())
                    .eq(ServiceOrderItem::getVoidCause, BizConsts.VOID_BY_ORDER));
        } else {
            items = itemMapper.selectList(Wrappers.<ServiceOrderItem>lambdaQuery()
                    .eq(ServiceOrderItem::getOrderId, order.getId())
                    .eq(ServiceOrderItem::getRecordStatus, BizConsts.RECORD_NORMAL));
        }
        vo.setItems(items);
        return vo;
    }
}
