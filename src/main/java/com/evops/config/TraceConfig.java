package com.evops.config;

import com.evops.common.trace.TraceTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 链路追踪基础设施：调用轨迹异步落库线程池。
 * TaskDecorator 负责把 TraceContext 接力到异步线程。
 */
@Configuration
public class TraceConfig {
    @Bean(name = "traceTaskExecutor")
    public ThreadPoolTaskExecutor traceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(new TraceTaskDecorator());
        executor.initialize();
        return executor;
    }
}
