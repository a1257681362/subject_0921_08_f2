package com.evops.common.log;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.evops.common.Masks;

/**
 * Logback 消息打码转换器（格式符 %mmsg）：
 * 敏感字段入日志前的兜底打码，业务代码漏打码时由它拦截。
 */
public class MaskingMessageConverter extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return Masks.maskText(super.convert(event));
    }
}
