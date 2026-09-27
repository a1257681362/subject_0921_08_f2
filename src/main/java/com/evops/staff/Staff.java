package com.evops.staff;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

/**
 * 服务人员台账。
 */
@TableName("t_staff")
public class Staff extends BaseEntity {
    private String staffNo;
    private String name;
    private String phone;
    /** ACTIVE / SUSPENDED */
    private String status;
    /** NORMAL / VOIDED */
    private String recordStatus;
    @Version
    private Integer version;
    private String lastRequestNo;

    public String getStaffNo() {
        return staffNo;
    }

    public void setStaffNo(String staffNo) {
        this.staffNo = staffNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
