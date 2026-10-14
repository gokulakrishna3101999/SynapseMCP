package com.synapsemcp.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.repository.ChunkRepository;
import com.synapsemcp.security.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Closes a gap memory.md flagged explicitly: nothing had exercised actually *hitting* the rate limit
 * through a real call path, only RateLimiter in isolation. Uses the real RateLimiter (not mocked) with
 * DocumentService.upload - one of the 4 wired-in call sites - to prove the 11th call in a burst is
 * rejected with 429 before any write happens, not just that RateLimiter.checkAndConsume throws in the
 * abstract.
 */
class DocumentServiceRateLimitTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final DocumentService service = new DocumentService(documents, jobs, knowledgeBases, storage,
            chunks, mock(AuditLogService.class), new RateLimiter());

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        when(knowledgeBases.findByTenantId(tenantId)).thenReturn(Optional.of(mock(KnowledgeBase.class)));
        when(documents.saveAndFlush(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobs.saveAndFlush(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storage.stageFile(any(), any(), anyString(), any(), anyString()))
                .thenReturn("tenants/x/staging/y/f");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theEleventhUploadInABurstIsRejectedBeforeAnyWrite() {
        for (int i = 0; i < RateLimiter.STRICT_LIMIT; i++) {
            service.upload(new MockMultipartFile("file", "a" + i + ".txt", null, new byte[10]));
        }

        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "overflow.txt", null, new byte[10])))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(documents, org.mockito.Mockito.times(RateLimiter.STRICT_LIMIT)).saveAndFlush(any(Document.class));
    }

    @Test
    void aDifferentTenantIsNotAffectedByAnotherTenantsBurst() {
        for (int i = 0; i < RateLimiter.STRICT_LIMIT; i++) {
            service.upload(new MockMultipartFile("file", "a" + i + ".txt", null, new byte[10]));
        }

        UUID otherTenant = UUID.randomUUID();
        TenantContext.setTenantId(otherTenant);
        when(knowledgeBases.findByTenantId(otherTenant)).thenReturn(Optional.of(mock(KnowledgeBase.class)));

        service.upload(new MockMultipartFile("file", "other-tenant.txt", null, new byte[10]));

        verify(documents, org.mockito.Mockito.times(RateLimiter.STRICT_LIMIT + 1)).saveAndFlush(any(Document.class));
    }
}
