package com.evops.order.dto;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 服务单新增 / 变更请求。主表与明细一并提交，同事务落库。
 */
public class OrderSaveRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    @NotBlank(message = "工单号不能为空")
    private String orderNo;
    @NotBlank(message = "客户姓名不能为空")
    private String customerName;
    private String customerPhone;
    private String address;
    @NotNull(message = "服务日期不能为空")
    private LocalDate serviceDate;
    @NotBlank(message = "时段不能为空")
    @Pattern(regexp = "AM|PM|ALLDAY", message = "时段仅支持 AM/PM/ALLDAY")
    private String timeSlot;
    @NotEmpty(message = "服务明细不能为空")
    @Valid
    private List<Item> items;
    /** 变更时必带（乐观锁） */
    private Integer version;

    public static class Item {
        @NotBlank(message = "明细名称不能为空")
        private String itemName;
        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        private Integer quantity;
        @NotNull(message = "单价不能为空")
        @DecimalMin(value = "0", message = "单价不能为负")
        private BigDecimal price;

        public String getItemName() {
            return itemName;
        }

        public void setItemName(String itemName) {
            this.itemName = itemName;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

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

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
