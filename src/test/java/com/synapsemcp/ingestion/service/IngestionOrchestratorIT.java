package com.synapsemcp.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.synapsemcp.auth.entity.Tenant;
import com.synapsemcp.auth.repository.TenantRepository;
import com.synapsemcp.config.S3Config;
import com.synapsemcp.config.StorageProperties;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.ingestion.entity.JobStatus;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.entity.Chunk;
import com.synapsemcp.rag.repository.ChunkRepository;
import com.synapsemcp.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Drives the whole Milestone 3.4 pipeline against real Postgres, real S3 and the real Gemini embedding
 * API: upload -> async orchestrator -> INDEXED, chunks persisted with 768d vectors, S3 object purged.
 * Uses a .txt fixture (Tier 2 / TextDocumentParser) so no Gemini chat quota is spent, only one real
 * embedding call. Run with: ./mvnw test -Dtest=IngestionOrchestratorIT
 */
@EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY", matches = ".+")
@SpringBootTest
class IngestionOrchestratorIT {

    @Autowired
    TenantRepository tenantRepository;
    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    IngestionJobRepository jobRepository;
    @Autowired
    ChunkRepository chunkRepository;
    @Autowired
    DocumentService documentService;
    @Autowired
    IngestionOrchestrator orchestrator;
    @Autowired
    S3Client s3Client;
    @Autowired
    StorageProperties storageProperties;

    private UUID tenantId;

    @AfterEach
    void tearDown() {
        if (tenantId != null) {
            tenantRepository.deleteById(tenantId);
        }
        TenantContext.clear();
    }

    @Test
    void uploadIsFullyIngestedAndStagedFileIsPurged() {
        Tenant tenant = new Tenant();
        tenant.setName("ingestion-orchestrator-it");
        tenant = tenantRepository.save(tenant);
        tenantId = tenant.getId();

        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenantId);
        kb = knowledgeBaseRepository.save(kb);
        UUID knowledgeBaseId = kb.getId();

        TenantContext.setTenantId(tenantId);
        byte[] content = ("SynapseMCP ingestion orchestrator end-to-end check.\n\n"
                + "This paragraph exists only so the chunker has real paragraphs to split on fence post marker ABCXYZ.")
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "orchestrator-it.txt", "text/plain", content);

        UploadResponse response = documentService.upload(file);
        TenantContext.clear();

        orchestrator.processDocumentAsync(response.jobId(), response.documentId(), tenantId);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Document document = documentRepository.findById(response.documentId()).orElseThrow();
            assertThat(document.getStatus()).isIn(DocumentStatus.INDEXED, DocumentStatus.FAILED);
        });

        Document document = documentRepository.findById(response.documentId()).orElseThrow();
        String storagePathForAssertion = extractOriginalStoragePath(response, tenantId);
        if (document.getStatus() == DocumentStatus.FAILED) {
            throw new AssertionError("Ingestion failed: "
                    + jobRepository.findFirstByDocumentIdOrderByCreatedAtDesc(response.documentId())
                            .map(j -> j.getErrorMessage()).orElse("unknown"));
        }

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.isRawFilePurged()).isTrue();
        assertThat(document.getStoragePath()).isNull();
        assertThat(jobRepository.findFirstByDocumentIdOrderByCreatedAtDesc(response.documentId()).orElseThrow()
                .getStatus()).isEqualTo(JobStatus.COMPLETED);

        List<Chunk> chunks = chunkRepository.findAll().stream()
                .filter(c -> c.getDocumentId().equals(response.documentId()))
                .toList();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getEmbedding()).hasSize(Chunk.EMBEDDING_DIMENSIONS);
        assertThat(chunks.get(0).getKnowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(chunks.stream().map(Chunk::getContent).toList().toString()).contains("ABCXYZ");

        // The staged object must really be gone from S3, not just marked purged in Postgres.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s3Client.getObject(
                        software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                                .bucket(storageProperties.bucket())
                                .key(storagePathForAssertion)
                                .build()))
                .isInstanceOf(NoSuchKeyException.class);
    }

    /** The key follows DocumentStorageService's fixed layout; reconstructed since the DB field is now null. */
    private static String extractOriginalStoragePath(UploadResponse response, UUID tenantId) {
        return "tenants/" + tenantId + "/staging/" + response.documentId() + "/orchestrator-it.txt";
    }
}
