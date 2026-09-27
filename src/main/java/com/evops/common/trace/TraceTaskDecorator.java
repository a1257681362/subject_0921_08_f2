package com.evops.common.trace;

import org.springframework.core.task.TaskDecorator;

/**
 * 线程池装饰器：任务提交时自动包一层 {@link TraceContext#wrap}，
 * 异步任务无需感知即可接力 TraceId。
 */
public class TraceTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        return TraceContext.wrap(runnable);
    }
}
