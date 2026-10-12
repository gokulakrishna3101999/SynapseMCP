package com.synapsemcp.security;

import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/** Carries the submitting thread's tenant onto the async worker thread and restores the worker's own afterwards. */
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UUID submitterTenant = TenantContext.getTenantId();
        return () -> {
            UUID previous = TenantContext.getTenantId();
            try {
                apply(submitterTenant);
                runnable.run();
            } finally {
                apply(previous);
            }
        };
    }

    private static void apply(UUID tenantId) {
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        } else {
            TenantContext.clear();
        }
    }
}
