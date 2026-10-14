package com.synapsemcp.common.service;

import com.synapsemcp.common.entity.AuditLog;
import com.synapsemcp.common.repository.AuditLogRepository;
import com.synapsemcp.security.RlsSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean (not a method on {@link AuditLogService} itself) specifically so
 * {@code @Transactional(REQUIRES_NEW)} actually takes effect: Spring's proxy-based AOP does not
 * intercept a self-invoked method call, so a caller within {@code AuditLogService} calling another
 * method on {@code this} would silently run in whatever transaction (or none) already exists.
 */
@Component
class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @PersistenceContext
    private EntityManager entityManager;

    AuditLogWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(UUID tenantId, UUID userId, String action, String resourceType, UUID resourceId,
               Map<String, Object> metadata) {
        RlsSupport.bindTenant(entityManager, tenantId);
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        if (metadata != null) {
            entry.setMetadata(metadata);
        }
        auditLogRepository.save(entry);
    }
}
