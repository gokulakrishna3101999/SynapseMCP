package com.synapsemcp.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.service.DocumentStorageService;
import com.synapsemcp.document.service.IngestionProgressSseService;
import com.synapsemcp.ingestion.chunking.DynamicChunker;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.entity.JobStatus;
import com.synapsemcp.ingestion.extraction.JavaParserExtractor;
import com.synapsemcp.ingestion.extraction.MultimodalLlmExtractor;
import com.synapsemcp.ingestion.extraction.Tier2ExtractionProperties;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.repository.ChunkRepository;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Tier 2 parsing (unlike Tier 1) was unbounded until this fix: a file can be up to 10MiB and a
 * pathologically structured one can take far longer to parse than its size suggests. Proves the new
 * {@code synapse.ingestion.tier2.timeout} actually fails the job - including purging the staged file -
 * rather than hanging forever, and does so within roughly the configured timeout, not the parser's
 * (much longer) actual completion time.
 */
class IngestionOrchestratorTier2TimeoutTest {

    @Test
    void slowTier2ParsingFailsTheJobAfterTheConfiguredTimeout() throws Exception {
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionJobRepository jobs = mock(IngestionJobRepository.class);
        DocumentStorageService storage = mock(DocumentStorageService.class);
        JavaParserExtractor tier2Extractor = mock(JavaParserExtractor.class);
        MultimodalLlmExtractor tier1Extractor = mock(MultimodalLlmExtractor.class);
        IngestionProgressSseService sseService = mock(IngestionProgressSseService.class);

        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        Document document = new Document();
        document.setId(documentId);
        document.setName("pathological.txt");
        document.setContentType("text/plain");
        document.setIngestionTier(IngestionTier.TIER_2_JAVA_PARSER);
        String stagingKey = "tenants/" + tenantId + "/staging/" + documentId + "/pathological.txt";
        document.setStoragePath(stagingKey);

        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setDocumentId(documentId);

        when(documents.findById(documentId)).thenReturn(Optional.of(document));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(storage.loadStagedFile(any())).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        // The parser call blocks far longer than the configured timeout - simulating a pathological file.
        when(tier2Extractor.extract(any())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            throw new AssertionError("should have been abandoned before completing");
        });
        // This 5-byte file is well under the Tier 1 fallback's size cap, so the orchestrator attempts it
        // after Tier 2's timeout; stub it to also fail so the test still ends in FAILED either way.
        when(tier1Extractor.extract(any()))
                .thenThrow(new com.synapsemcp.ingestion.extraction.DocumentExtractionException("Tier 1 also failed"));

        IngestionOrchestrator orchestrator = new IngestionOrchestrator(documents, jobs, mock(ChunkRepository.class),
                storage, tier1Extractor, tier2Extractor, mock(DynamicChunker.class),
                mock(EmbeddingModel.class), sseService, new Tier2ExtractionProperties(Duration.ofMillis(200)));

        long start = System.nanoTime();
        orchestrator.processDocumentAsync(jobId, documentId, tenantId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("must fail near the 200ms timeout, not wait for the 5s parser call")
                .isLessThan(2_000);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage()).contains("timed out");
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(document.getStoragePath()).as("purged on failure, per ADR-07").isNull();
        verify(storage).purgeStagedFile(stagingKey);
        verify(sseService).broadcastError(documentId, document.getErrorMessage());
    }
}
