package com.evops.staff;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.evops.common.BizConsts;
import com.evops.common.VoidRequest;
import com.evops.order.ServiceOrder;
import com.evops.order.ServiceOrderMapper;
import com.evops.staff.dto.LeaveRequest;
import com.evops.staff.dto.StaffSaveRequest;
import com.evops.system.IdempotencyService;
import com.evops.system.OperationLogger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 服务人员台账：增改、冲销/恢复、休假登记。
 * 所有写操作走幂等执行器，物理删除一律禁止。
 */
@Service
public class StaffService {
    private final StaffMapper staffMapper;
    private final StaffLeaveMapper leaveMapper;
    private final ServiceOrderMapper orderMapper;
    private final IdempotencyService idempotencyService;
    private final OperationLogger operationLogger;

    public StaffService(StaffMapper staffMapper,
                        StaffLeaveMapper leaveMapper,
                        ServiceOrderMapper orderMapper,
                        IdempotencyService idempotencyService,
                        OperationLogger operationLogger) {
        this.staffMapper = staffMapper;
        this.leaveMapper = leaveMapper;
        this.orderMapper = orderMapper;
        this.idempotencyService = idempotencyService;
        this.operationLogger = operationLogger;
    }

    public Staff create(StaffSaveRequest req) {
        return idempotencyService.run(req.getRequestNo(), "STAFF_CREATE", Staff.class, () -> {
            Long exists = staffMapper.selectCount(Wrappers.<Staff>lambdaQuery()
                    .eq(Staff::getStaffNo, req.getStaffNo()));
            if (exists != null && exists > 0) {
                throw new IllegalArgumentException("人员编号已存在：" + req.getStaffNo());
            }
            Staff staff = new Staff();
            staff.setStaffNo(req.getStaffNo());
            staff.setName(req.getName());
            staff.setPhone(req.getPhone());
            staff.setStatus(BizConsts.STAFF_ACTIVE);
            staff.setRecordStatus(BizConsts.RECORD_NORMAL);
            staff.setVersion(0);
            staff.setLastRequestNo(req.getRequestNo());
            staffMapper.insert(staff);
            operationLogger.log("STAFF", staff.getId(), "CREATE", req.getRequestNo(),
                    "新增人员 " + staff.getName());
            return staff;
        });
    }

    public Staff update(Long id, StaffSaveRequest req) {
        return idempotencyService.run(req.getRequestNo(), "STAFF_UPDATE", Staff.class, () -> {
            Staff staff = mustGetNormal(id);
            checkVersion(req.getVersion(), staff.getVersion());
            if (!staff.getStaffNo().equals(req.getStaffNo())) {
                Long dup = staffMapper.selectCount(Wrappers.<Staff>lambdaQuery()
                        .eq(Staff::getStaffNo, req.getStaffNo())
                        .ne(Staff::getId, id));
                if (dup != null && dup > 0) {
                    throw new IllegalArgumentException("人员编号已存在：" + req.getStaffNo());
                }
                staff.setStaffNo(req.getStaffNo());
            }
            staff.setName(req.getName());
            staff.setPhone(req.getPhone());
            staff.setLastRequestNo(req.getRequestNo());
            if (staffMapper.updateById(staff) == 0) {
                throw new IllegalArgumentException("记录已被他人变更，请刷新后重试");
            }
            operationLogger.log("STAFF", staff.getId(), "UPDATE", req.getRequestNo(),
                    "变更人员 " + staff.getName());
            return staff;
        });
    }

    public Staff voidStaff(Long id, VoidRequest req) {
        return idempotencyService.run(req.getRequestNo(), "STAFF_VOID", Staff.class, () -> {
            Staff staff = mustGetNormal(id);
            Long assigned = orderMapper.selectCount(Wrappers.<ServiceOrder>lambdaQuery()
                    .eq(ServiceOrder::getStaffId, id)
                    .eq(ServiceOrder::getRecordStatus, BizConsts.RECORD_NORMAL)
                    .eq(ServiceOrder::getStatus, BizConsts.ORDER_ASSIGNED));
            if (assigned != null && assigned > 0) {
                throw new IllegalArgumentException("存在进行中的派单，不可冲销");
            }
            staff.setRecordStatus(BizConsts.RECORD_VOIDED);
            staff.setLastRequestNo(req.getRequestNo());
            staffMapper.updateById(staff);
            operationLogger.log("STAFF", staff.getId(), "VOID", req.getRequestNo(),
                    "冲销人员 " + staff.getName());
            return staff;
        });
    }

    public Staff restore(Long id, VoidRequest req) {
        return idempotencyService.run(req.getRequestNo(), "STAFF_RESTORE", Staff.class, () -> {
            Staff staff = mustGet(id);
            if (!BizConsts.RECORD_VOIDED.equals(staff.getRecordStatus())) {
                throw new IllegalArgumentException("记录未冲销，无需恢复");
            }
            staff.setRecordStatus(BizConsts.RECORD_NORMAL);
            staff.setLastRequestNo(req.getRequestNo());
            staffMapper.updateById(staff);
            operationLogger.log("STAFF", staff.getId(), "RESTORE", req.getRequestNo(),
                    "恢复人员 " + staff.getName());
            return staff;
        });
    }

    public StaffLeave addLeave(Long staffId, LeaveRequest req) {
        return idempotencyService.run(req.getRequestNo(), "LEAVE_CREATE", StaffLeave.class, () -> {
            Staff staff = mustGetNormal(staffId);
            Long dup = leaveMapper.selectCount(Wrappers.<StaffLeave>lambdaQuery()
                    .eq(StaffLeave::getStaffId, staffId)
                    .eq(StaffLeave::getLeaveDate, req.getLeaveDate())
                    .eq(StaffLeave::getRecordStatus, BizConsts.RECORD_NORMAL));
            if (dup != null && dup > 0) {
                throw new IllegalArgumentException("当日已有休假记录");
            }
            StaffLeave leave = new StaffLeave();
            leave.setStaffId(staffId);
            leave.setLeaveDate(req.getLeaveDate());
            leave.setReason(req.getReason());
            leave.setRecordStatus(BizConsts.RECORD_NORMAL);
            leave.setVersion(0);
            leave.setLastRequestNo(req.getRequestNo());
            leaveMapper.insert(leave);
            operationLogger.log("LEAVE", leave.getId(), "CREATE", req.getRequestNo(),
                    staff.getName() + " 登记休假 " + req.getLeaveDate());
            return leave;
        });
    }

    public StaffLeave voidLeave(Long leaveId, VoidRequest req) {
        return idempotencyService.run(req.getRequestNo(), "LEAVE_VOID", StaffLeave.class, () -> {
            StaffLeave leave = leaveMapper.selectById(leaveId);
            if (leave == null || !BizConsts.RECORD_NORMAL.equals(leave.getRecordStatus())) {
                throw new IllegalArgumentException("休假记录不存在或已冲销");
            }
            leave.setRecordStatus(BizConsts.RECORD_VOIDED);
            leave.setLastRequestNo(req.getRequestNo());
            leaveMapper.updateById(leave);
            operationLogger.log("LEAVE", leave.getId(), "VOID", req.getRequestNo(),
                    "冲销休假 " + leave.getLeaveDate());
            return leave;
        });
    }

    public List<Staff> list(String keyword, boolean includeVoided) {
        return staffMapper.selectList(Wrappers.<Staff>lambdaQuery()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Staff::getName, keyword)
                        .or()
                        .like(Staff::getStaffNo, keyword))
                .eq(!includeVoided, Staff::getRecordStatus, BizConsts.RECORD_NORMAL)
                .orderByDesc(Staff::getId));
    }

    public Staff get(Long id) {
        return mustGet(id);
    }

    public List<StaffLeave> listLeaves(Long staffId) {
        return leaveMapper.selectList(Wrappers.<StaffLeave>lambdaQuery()
                .eq(StaffLeave::getStaffId, staffId)
                .eq(StaffLeave::getRecordStatus, BizConsts.RECORD_NORMAL)
                .orderByAsc(StaffLeave::getLeaveDate));
    }

    private Staff mustGet(Long id) {
        Staff staff = staffMapper.selectById(id);
        if (staff == null) {
            throw new IllegalArgumentException("人员不存在：" + id);
        }
        return staff;
    }

    private Staff mustGetNormal(Long id) {
        Staff staff = mustGet(id);
        if (!BizConsts.RECORD_NORMAL.equals(staff.getRecordStatus())) {
            throw new IllegalArgumentException("人员已冲销：" + id);
        }
        return staff;
    }

    private void checkVersion(Integer requestVersion, Integer currentVersion) {
        if (requestVersion == null) {
            throw new IllegalArgumentException("变更请求必须携带版本号");
        }
        if (!requestVersion.equals(currentVersion)) {
            throw new IllegalArgumentException("记录已被他人变更，请刷新后重试");
        }
    }
}
