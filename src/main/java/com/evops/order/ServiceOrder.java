package com.evops.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.time.LocalDate;

/**
 * 服务单主表。工单号唯一；与明细同生共死。
 */
@TableName("t_service_order")
public class ServiceOrder extends BaseEntity {
    private String orderNo;
    private String customerName;
    private String customerPhone;
    private String address;
    private LocalDate serviceDate;
    /** AM / PM / ALLDAY */
    private String timeSlot;
    /** PENDING / ASSIGNED */
    private String status;
    private Long staffId;
    /** NORMAL / VOIDED */
    private String recordStatus;
    @Version
    private Integer version;
    private String lastRequestNo;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public void setServiceDate(LocalDate serviceDate) {
        this.serviceDate = serviceDate;
    }

    public String getTimeSlot() {
        return timeSlot;
    }

    public void setTimeSlot(String timeSlot) {
        this.timeSlot = timeSlot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getStaffId() {
        return staffId;
    }

    public void setStaffId(Long staffId) {
        this.staffId = staffId;
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
