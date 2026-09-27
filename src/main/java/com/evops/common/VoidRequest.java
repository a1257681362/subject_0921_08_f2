package com.evops.common;

import javax.validation.constraints.NotBlank;

/**
 * 冲销 / 恢复通用请求：必须携带请求号（幂等键）。
 */
public class VoidRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }
}
