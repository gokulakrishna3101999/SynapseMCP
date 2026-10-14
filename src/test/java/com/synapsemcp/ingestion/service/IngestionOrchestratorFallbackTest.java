package com.synapsemcp.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.service.DocumentStorageService;
import com.synapsemcp.document.service.IngestionProgressSseService;
import com.synapsemcp.ingestion.chunking.ChunkPayload;
import com.synapsemcp.ingestion.chunking.DynamicChunker;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.entity.JobStatus;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import com.synapsemcp.ingestion.extraction.JavaParserExtractor;
import com.synapsemcp.ingestion.extraction.MultimodalLlmExtractor;
import com.synapsemcp.ingestion.extraction.Tier2ExtractionProperties;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.repository.ChunkRepository;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Covers the user-requested reordering (memory.md 12j): Java parser (Tier 2) is always tried first, for
 * every category and size; the Gemini fallback (Tier 1) is only attempted on a Tier 2 failure, and only
 * for files up to Tier 1's 2 MiB limit; if that also fails, or is skipped for being too large, the job
 * fails with both reasons named. The happy path (Tier 2 succeeds outright) is already covered by
 * {@code IngestionOrchestratorIT} against real infrastructure.
 */
class IngestionOrchestratorFallbackTest {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    private DocumentRepository documents;
    private IngestionJobRepository jobs;
    private DocumentStorageService storage;
    private JavaParserExtractor tier2Extractor;
    private MultimodalLlmExtractor tier1Extractor;
    private ChunkRepository chunkRepository;
    private DynamicChunker chunker;
    private EmbeddingModel embeddingModel;
    private IngestionProgressSseService sseService;
    private Document document;
    private IngestionJob job;

    private IngestionOrchestrator newOrchestrator() {
        documents = mock(DocumentRepository.class);
        jobs = mock(IngestionJobRepository.class);
        storage = mock(DocumentStorageService.class);
        tier2Extractor = mock(JavaParserExtractor.class);
        tier1Extractor = mock(MultimodalLlmExtractor.class);
        chunkRepository = mock(ChunkRepository.class);
        chunker = mock(DynamicChunker.class);
        embeddingModel = mock(EmbeddingModel.class);
        sseService = mock(IngestionProgressSseService.class);

        document = new Document();
        document.setId(DOCUMENT_ID);
        document.setName("report.txt");
        document.setContentType("text/plain");
        document.setIngestionTier(IngestionTier.TIER_2_JAVA_PARSER);
        document.setStoragePath("tenants/" + TENANT_ID + "/staging/" + DOCUMENT_ID + "/report.txt");

        job = new IngestionJob();
        job.setId(JOB_ID);
        job.setDocumentId(DOCUMENT_ID);

        when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job));

        return new IngestionOrchestrator(documents, jobs, chunkRepository, storage, tier1Extractor, tier2Extractor,
                chunker, embeddingModel, sseService, new Tier2ExtractionProperties(Duration.ofSeconds(30)));
    }

    private void stageContent(byte[] content) {
        when(storage.loadStagedFile(any())).thenReturn(new ByteArrayInputStream(content));
    }

    @Test
    void tier2FailureFallsBackToTier1AndTheTierFieldReflectsWhatActuallySucceeded() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        stageContent("small file content".getBytes());
        when(tier2Extractor.extract(any())).thenThrow(new DocumentExtractionException("no readable text found"));
        when(tier1Extractor.extract(any())).thenReturn(new ExtractionResult("# Recovered via Gemini"));
        when(chunker.chunk(org.mockito.ArgumentMatchers.eq("# Recovered via Gemini"), any()))
                .thenReturn(List.of(new ChunkPayload(0, "chunk")));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[768]));

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getIngestionTier()).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        verify(tier1Extractor).extract(any());
    }

    @Test
    void bothTiersFailingProducesACombinedErrorNamingBoth() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        stageContent("small file content".getBytes());
        when(tier2Extractor.extract(any())).thenThrow(new DocumentExtractionException("Java parser exploded"));
        when(tier1Extractor.extract(any())).thenThrow(new DocumentExtractionException("Gemini also exploded"));

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage()).contains("Java parser exploded").contains("Gemini also exploded");
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(sseService).broadcastError(DOCUMENT_ID, document.getErrorMessage());
    }

    @Test
    void aFileOverTheTierOneLimitGetsNoFallbackAttemptAtAll() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        byte[] oversized = new byte[(int) MultimodalLlmExtractor.TIER_1_MAX_BYTES + 1];
        stageContent(oversized);
        when(tier2Extractor.extract(any())).thenThrow(new DocumentExtractionException("Java parser exploded"));

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage()).contains("Java parser exploded").contains("not attempted");
        verify(tier1Extractor, never()).extract(any());
    }
}
