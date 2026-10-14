package com.synapsemcp.security;

import java.util.UUID;

/** Holds the active tenant for the current (virtual) thread. Always cleared in a {@code finally}. */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    /** @return the active tenant, or {@code null} when none is bound. */
    public static UUID getTenantId() {
        return CURRENT.get();
    }

    public static UUID requireTenantId() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant bound to the current thread");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
