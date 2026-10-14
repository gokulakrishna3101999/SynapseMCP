package com.synapsemcp.security;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Replaces the planned {@code TenantRlsInterceptor}: right after every transaction begins, binds the
 * {@link TenantContext} tenant to the PostgreSQL session variable that the RLS policies read.
 * Because the setting is transaction-local, pooled connections never leak a tenant to the next borrower.
 */
public class TenantRlsTransactionManager extends JpaTransactionManager {

    public TenantRlsTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        EntityManagerHolder holder = (EntityManagerHolder)
                TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        RlsSupport.bindTenant(holder.getEntityManager(), tenantId);
    }
}
