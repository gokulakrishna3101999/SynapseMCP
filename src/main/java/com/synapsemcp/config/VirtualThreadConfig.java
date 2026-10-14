package com.synapsemcp.config;

import com.synapsemcp.security.TenantAwareTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class VirtualThreadConfig {

    /** Executor for {@code @Async("ingestionExecutor")}: one virtual thread per task, tenant context propagated. */
    @Bean(name = "ingestionExecutor")
    public AsyncTaskExecutor ingestionExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ingest-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        return executor;
    }
}
