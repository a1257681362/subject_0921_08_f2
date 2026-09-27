package com.evops.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感字段打码：任何内容写入日志（含操作留痕）前必须过一遍。
 */
public final class Masks {
    /** 大陆手机号：保留前 3 后 4 */
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)(\\d{4})(\\d{4})");
    /** 18 位身份证：保留前 4 后 4 */
    private static final Pattern ID_CARD = Pattern.compile("(\\d{4})\\d{10}(\\d{3}[0-9Xx])");

    private Masks() {
    }

    /** 文本级打码：身份证 / 手机号（先长后短，避免身份证中的数字串被手机号规则误吞），日志输出前统一调用 */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = maskAll(ID_CARD, text, "$1**********$2");
        return maskAll(PHONE, masked, "$1****$3");
    }

    /** 手机号打码：138****0000；非手机号原样返回 */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return PHONE.matcher(phone).replaceAll("$1****$3");
    }

    /** 姓名打码：保留首字，其余以 * 代替 */
    public static String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        sb.append(name.charAt(0));
        for (int i = 1; i < name.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    private static String maskAll(Pattern pattern, String text, String replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
