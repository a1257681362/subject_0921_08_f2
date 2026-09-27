package com.evops.staff.dto;

import javax.validation.constraints.NotBlank;

/**
 * 人员新增 / 变更请求。变更时必须携带当前版本号。
 */
public class StaffSaveRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "人员编号不能为空")
    private String staffNo;
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String phone;
    /** 变更时必带（乐观锁） */
    private Integer version;

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
