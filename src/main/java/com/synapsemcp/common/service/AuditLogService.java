package com.synapsemcp.common.service;

import com.synapsemcp.common.entity.AuditLog;
import com.synapsemcp.common.repository.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records security-sensitive events (signup/signin, MCP key create/revoke, document upload/delete) -
 * never chat queries or answers, per the architecture's "no chat transcripts persisted" invariant.
 * Never throws itself: a logging failure must not break the request it is auditing.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final AuditLogWriter independentWriter;

    public AuditLogService(AuditLogRepository auditLogRepository, AuditLogWriter independentWriter) {
        this.auditLogRepository = auditLogRepository;
        this.independentWriter = independentWriter;
    }

    /**
     * Writes within the caller's own (usually already-open) transaction - the right choice whenever the
     * resource being audited, or its RLS tenant binding, may have been created or bound in this very
     * transaction: a separate transaction would not see uncommitted work from this one.
     */
    public void record(UUID tenantId, UUID userId, String action, String resourceType, UUID resourceId,
                        Map<String, Object> metadata) {
        try {
            auditLogRepository.save(buildEntry(tenantId, userId, action, resourceType, resourceId, metadata));
        } catch (RuntimeException ex) {
            log.error("Failed to write audit log: tenant={} action={} resourceType={} resourceId={}",
                    tenantId, action, resourceType, resourceId, ex);
        }
    }

    /**
     * Writes in its own independent transaction (via {@link AuditLogWriter}), so the record survives
     * even if the caller's own transaction later rolls back or fails. Only correct for events whose
     * referenced tenant/user already exists from a prior, already-committed transaction (e.g.
     * SIGNIN_FAILURE against an existing user) - a REQUIRES_NEW transaction cannot see a row the
     * caller's own still-open transaction created but hasn't committed yet.
     */
    public void recordIndependently(UUID tenantId, UUID userId, String action, String resourceType, UUID resourceId,
                                     Map<String, Object> metadata) {
        try {
            independentWriter.write(tenantId, userId, action, resourceType, resourceId, metadata);
        } catch (RuntimeException ex) {
            log.error("Failed to write independent audit log: tenant={} action={} resourceType={} resourceId={}",
                    tenantId, action, resourceType, resourceId, ex);
        }
    }

    private static AuditLog buildEntry(UUID tenantId, UUID userId, String action, String resourceType,
                                        UUID resourceId, Map<String, Object> metadata) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        if (metadata != null) {
            entry.setMetadata(metadata);
        }
        return entry;
    }
}
