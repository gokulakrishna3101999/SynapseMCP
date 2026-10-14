package com.synapsemcp.security;

import com.synapsemcp.config.VirtualThreadConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAwareTaskDecoratorTest {

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void propagatesTenantToVirtualThreadAndDoesNotLeakIt() throws Exception {
        AsyncTaskExecutor executor = new VirtualThreadConfig().ingestionExecutor();
        UUID tenantId = UUID.randomUUID();
        AtomicReference<UUID> seen = new AtomicReference<>();
        AtomicBoolean virtual = new AtomicBoolean();

        TenantContext.setTenantId(tenantId);
        executor.submit(() -> {
            seen.set(TenantContext.getTenantId());
            virtual.set(Thread.currentThread().isVirtual());
        }).get();

        assertThat(seen.get()).isEqualTo(tenantId);
        assertThat(virtual.get()).isTrue();

        TenantContext.clear();
        AtomicReference<UUID> afterClear = new AtomicReference<>(UUID.randomUUID());
        executor.submit(() -> afterClear.set(TenantContext.getTenantId())).get();
        assertThat(afterClear.get()).isNull();
    }
}
