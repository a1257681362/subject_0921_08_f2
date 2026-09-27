package com.evops.config;

import com.evops.common.trace.MdcTaskDecorator;
import com.evops.common.trace.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 链路追踪装配：入口过滤器 + 异步 MDC 接力。
 * 唯一的 TaskDecorator 会被 Spring Boot 应用到默认 applicationTaskExecutor，
 * 因此 @Async 与直接注入 TaskExecutor 的异步任务都会自动带上 traceId/orderNo。
 */
@Configuration
@EnableAsync
public class TraceConfig {
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
