package com.evops.order.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 改期请求：目标日期与人员休假/排班冲突时自动顺延。
 */
public class RescheduleRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotNull(message = "目标日期不能为空")
    private LocalDate serviceDate;

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public void setServiceDate(LocalDate serviceDate) {
        this.serviceDate = serviceDate;
    }
}
