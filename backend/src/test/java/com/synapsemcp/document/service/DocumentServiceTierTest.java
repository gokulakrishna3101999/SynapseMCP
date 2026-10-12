package com.synapsemcp.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.extraction.IngestionTierRouter;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.security.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** The tier recorded at upload time must follow IngestionTierRouter, not size alone. */
class DocumentServiceTierTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final DocumentService service =
            new DocumentService(documents, jobs, knowledgeBases, storage, new IngestionTierRouter());

    @BeforeEach
    void setUp() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        when(knowledgeBases.findByTenantId(tenantId)).thenReturn(Optional.of(mock(KnowledgeBase.class)));
        when(documents.saveAndFlush(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobs.saveAndFlush(any(IngestionJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storage.stageFile(any(), any(), anyString(), any(), anyLong(), anyString())).thenReturn("tenants/x/staging/y/f");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private IngestionTier tierOf(String filename, int size) {
        UploadResponse response = service.upload(new MockMultipartFile("file", filename, null, new byte[size]));
        return response.ingestionTier();
    }

    @Test
    void smallPdfAndImageAreTier1() {
        assertThat(tierOf("a.pdf", 1024)).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
        assertThat(tierOf("a.png", 1024)).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
    }

    @Test
    void exactlyTwoMibPdfIsTier1() {
        assertThat(tierOf("a.pdf", 2 * 1024 * 1024)).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
    }

    @Test
    void smallNonPdfImageFilesAreTier2() {
        assertThat(tierOf("a.docx", 1024)).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
        assertThat(tierOf("a.csv", 1024)).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
        assertThat(tierOf("a.html", 1024)).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
    }

    @Test
    void pdfAboveTwoMibIsTier2() {
        assertThat(tierOf("a.pdf", 2 * 1024 * 1024 + 1)).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
    }
}
