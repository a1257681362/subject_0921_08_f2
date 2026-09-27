package com.evops.order.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 派单请求：指定服务人员，系统校验休假与既有排班。
 */
public class AssignRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotNull(message = "服务人员不能为空")
    private Long staffId;

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public Long getStaffId() {
        return staffId;
    }

    public void setStaffId(Long staffId) {
        this.staffId = staffId;
    }
}
