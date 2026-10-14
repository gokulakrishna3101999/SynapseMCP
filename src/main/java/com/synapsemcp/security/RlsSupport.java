package com.synapsemcp.security;

import jakarta.persistence.EntityManager;

import java.util.UUID;

/** Binds {@code app.current_tenant_id} for the current database transaction (transaction-local, like SET LOCAL). */
public final class RlsSupport {

    private static final String SQL = "select set_config('app.current_tenant_id', :tenantId, true)";

    private RlsSupport() {
    }

    /** Must be called inside an active transaction; the setting reverts automatically at commit/rollback. */
    public static void bindTenant(EntityManager entityManager, UUID tenantId) {
        entityManager.createNativeQuery(SQL)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();
    }
}
