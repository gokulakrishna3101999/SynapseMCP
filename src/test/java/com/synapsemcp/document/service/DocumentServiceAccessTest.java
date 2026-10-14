package com.synapsemcp.document.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.repository.ChunkRepository;
import com.synapsemcp.security.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@code verifyOwnership} is the only guard in front of GET /{id}/progress-stream (the SSE endpoint has
 * no other tenant check): it must reject a document that does not belong to the active tenant, including
 * one that exists for a different tenant, not just a nonexistent id.
 */
class DocumentServiceAccessTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentService service = new DocumentService(documents, mock(IngestionJobRepository.class),
            mock(KnowledgeBaseRepository.class), mock(DocumentStorageService.class),
            mock(ChunkRepository.class), mock(AuditLogService.class), new RateLimiter());

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsUnknownDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        when(documents.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOwnership(documentId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsAnotherTenantsDocumentEvenThoughItExists() {
        UUID requestingTenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        // The document exists for a different tenant; findByIdAndTenantId only matches the requester's own rows.
        TenantContext.setTenantId(requestingTenantId);
        when(documents.findByIdAndTenantId(documentId, requestingTenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOwnership(documentId)).isInstanceOf(ApiException.class);
    }

    @Test
    void allowsTheOwningTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        when(documents.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(mock(Document.class)));

        assertThatCode(() -> service.verifyOwnership(documentId)).doesNotThrowAnyException();
    }
}
