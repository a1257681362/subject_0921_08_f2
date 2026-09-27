package com.evops.common;

import org.springframework.util.StringUtils;

/**
 * 敏感字段打码：只用于日志输出，落库与接口返回保持原文。
 */
public final class SensitiveMasker {
    private SensitiveMasker() {
    }

    /** 手机号：长度 ≥7 保留前 3 后 4（138****0000）；短号只留首字符 */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return phone;
        }
        int len = phone.length();
        if (len >= 7) {
            return phone.substring(0, 3) + "****" + phone.substring(len - 4);
        }
        if (len >= 2) {
            return phone.charAt(0) + "**";
        }
        return "*";
    }

    /** 姓名：保留首字，其余逐字打码（张*、王**） */
    public static String maskName(String name) {
        if (!StringUtils.hasText(name)) {
            return name;
        }
        int len = name.length();
        if (len == 1) {
            return "*";
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(name.charAt(0));
        for (int i = 1; i < len; i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    /** 地址：超过 6 字保留前 6 字，其余打码 */
    public static String maskAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return address;
        }
        int len = address.length();
        if (len > 6) {
            return address.substring(0, 6) + "****";
        }
        if (len > 2) {
            return address.substring(0, 2) + "****";
        }
        return "****";
    }
}
