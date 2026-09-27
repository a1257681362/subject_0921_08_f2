package com.evops.staff;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.time.LocalDate;

/**
 * 人员休假：派单与改期需避开。
 */
@TableName("t_staff_leave")
public class StaffLeave extends BaseEntity {
    private Long staffId;
    private LocalDate leaveDate;
    private String reason;
    private String recordStatus;
    @Version
    private Integer version;
    private String lastRequestNo;

    public Long getStaffId() {
        return staffId;
    }

    public void setStaffId(Long staffId) {
        this.staffId = staffId;
    }

    public LocalDate getLeaveDate() {
        return leaveDate;
    }

    public void setLeaveDate(LocalDate leaveDate) {
        this.leaveDate = leaveDate;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRecordStatus() {
        return recordStatus;
    }

    public void setRecordStatus(String recordStatus) {
        this.recordStatus = recordStatus;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getLastRequestNo() {
        return lastRequestNo;
    }

    public void setLastRequestNo(String lastRequestNo) {
        this.lastRequestNo = lastRequestNo;
    }
}
