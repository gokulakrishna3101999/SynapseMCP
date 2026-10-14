package com.synapsemcp.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.auth.dto.AuthRequests.SigninRequest;
import com.synapsemcp.auth.dto.AuthRequests.SignupRequest;
import com.synapsemcp.auth.dto.AuthResponse;
import com.synapsemcp.auth.entity.Tenant;
import com.synapsemcp.auth.repository.TenantRepository;
import com.synapsemcp.auth.service.AuthService;
import com.synapsemcp.common.entity.AuditLog;
import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.repository.AuditLogRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the two things that are easy to get wrong with {@code REQUIRES_NEW} + self-invocation: the
 * audit write actually lands in real Postgres (RLS correctly bound for its own independent
 * transaction), and - the entire point of using a separate transaction - a failure record survives even
 * when it is written moments before the caller's own transaction does nothing but roll back.
 * Run with: ./mvnw test -Dtest=AuditLogServiceIT
 */
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@SpringBootTest
class AuditLogServiceIT {

    @Autowired
    AuditLogService auditLogService;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    TenantRepository tenantRepository;
    @Autowired
    AuthService authService;
    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;

    private UUID tenantId;

    @AfterEach
    void tearDown() {
        if (tenantId != null) {
            tenantRepository.deleteById(tenantId);
        }
    }

    @Test
    void recordedEntryIsReallyPersistedWithTheRightFields() {
        Tenant tenant = new Tenant();
        tenant.setName("audit-log-it");
        tenant = tenantRepository.save(tenant);
        tenantId = tenant.getId();
        UUID resourceId = UUID.randomUUID();

        // user_id has a real FK to users(id) (nullable, ON DELETE SET NULL) - null is the valid way to
        // exercise this write path without also provisioning a real user row.
        auditLogService.record(tenantId, null, "DOCUMENT_UPLOAD", "DOCUMENT", resourceId,
                Map.of("fileName", "report.pdf"));

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .toList();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("DOCUMENT_UPLOAD");
        assertThat(row.getResourceType()).isEqualTo("DOCUMENT");
        assertThat(row.getResourceId()).isEqualTo(resourceId);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getMetadata()).containsEntry("fileName", "report.pdf");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void aFailureAuditRowSurvivesEvenThoughTheCallersOwnTransactionThrows() {
        String email = "audit-log-it-" + UUID.randomUUID() + "@example.com";
        AuthResponse signup = authService.signup(new SignupRequest(email, "password1234", "Audit Rollback Test"));
        tenantId = knowledgeBaseRepository.findById(signup.knowledgeBaseId()).orElseThrow().getTenantId();

        // AuthService.signin writes the SIGNIN_FAILURE audit row via AuditLogService (REQUIRES_NEW),
        // then throws ApiException, which rolls back signin's own @Transactional method. The audit row
        // must still be there - that is the entire reason REQUIRES_NEW was used instead of a plain
        // method call.
        assertThatThrownBy(() -> authService.signin(new SigninRequest(email, "wrong-password")))
                .isInstanceOf(ApiException.class);

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(r -> r.getTenantId().equals(tenantId) && "SIGNIN_FAILURE".equals(r.getAction()))
                .toList();
        assertThat(rows).hasSize(1);
    }
}
