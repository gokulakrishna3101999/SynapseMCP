package com.synapsemcp.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.entity.JobStatus;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.repository.ChunkRepository;
import com.synapsemcp.security.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@code delete} must not race {@code IngestionOrchestrator} (reject while a job is still in flight)
 * and must not leak the staged S3 object when it was never purged (a FAILED document whose own
 * purge-on-failure attempt also failed) - the gap this class was added to close.
 */
class DocumentServiceDeleteTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final DocumentService service = new DocumentService(documents, jobs,
            mock(KnowledgeBaseRepository.class), storage, chunks,
            mock(AuditLogService.class), new RateLimiter());

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Document documentWithStoragePath(String storagePath) {
        Document document = new Document();
        document.setName("fixture.txt");
        document.setId(documentId);
        document.setStoragePath(storagePath);
        return document;
    }

    @Test
    void rejectsDeleteWhileIngestionIsStillInFlight() {
        TenantContext.setTenantId(tenantId);
        when(documents.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(documentWithStoragePath(null)));
        IngestionJob job = new IngestionJob();
        job.setStatus(JobStatus.EMBEDDING);
        when(jobs.findFirstByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.delete(documentId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(documents, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void purgesAnUnpurgedFailedDocumentBeforeDeleting() {
        TenantContext.setTenantId(tenantId);
        Document document = documentWithStoragePath("tenants/x/staging/y/f");
        when(documents.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));
        IngestionJob job = new IngestionJob();
        job.setStatus(JobStatus.FAILED);
        when(jobs.findFirstByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(Optional.of(job));

        service.delete(documentId);

        verify(storage).purgeStagedFile("tenants/x/staging/y/f");
        verify(documents).delete(document);
    }

    @Test
    void deletingAnAlreadyPurgedCompletedDocumentSkipsThePurgeCall() {
        TenantContext.setTenantId(tenantId);
        Document document = documentWithStoragePath(null);
        when(documents.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));
        IngestionJob job = new IngestionJob();
        job.setStatus(JobStatus.COMPLETED);
        when(jobs.findFirstByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(Optional.of(job));

        service.delete(documentId);

        verify(storage, never()).purgeStagedFile(org.mockito.ArgumentMatchers.any());
        verify(documents).delete(document);
    }

    @Test
    void aPurgeFailureDoesNotBlockTheDelete() {
        TenantContext.setTenantId(tenantId);
        Document document = documentWithStoragePath("tenants/x/staging/y/f");
        when(documents.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));
        IngestionJob job = new IngestionJob();
        job.setStatus(JobStatus.FAILED);
        when(jobs.findFirstByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new RuntimeException("S3 down")).when(storage).purgeStagedFile("tenants/x/staging/y/f");

        service.delete(documentId);

        verify(documents).delete(document);
    }

    @Test
    void missingJobRowDoesNotBlockDeletion() {
        TenantContext.setTenantId(tenantId);
        Document document = documentWithStoragePath(null);
        when(documents.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));
        when(jobs.findFirstByDocumentIdOrderByCreatedAtDesc(documentId)).thenReturn(Optional.empty());

        service.delete(documentId);

        verify(documents).delete(document);
    }
}
