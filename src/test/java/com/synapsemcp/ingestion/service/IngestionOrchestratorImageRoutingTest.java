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
 * Covers the one deliberate exception to "Java parser always first" (user decision, see
 * IngestionOrchestrator's class/{@code extractSmallImage} javadoc): images under the 2 MiB Tier 1 limit
 * try Gemini (Tier 1) first, falling back to Tesseract OCR (Tier 2) only if Gemini fails. Images at or
 * over 2 MiB are unaffected - they keep going through {@code IngestionOrchestratorFallbackTest}'s
 * Tier-2-first path, since Tier 1 hard-rejects anything that large regardless.
 */
class IngestionOrchestratorImageRoutingTest {

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
        document.setName("photo.png");
        document.setContentType("image/png");
        document.setIngestionTier(IngestionTier.TIER_2_JAVA_PARSER);
        document.setStoragePath("tenants/" + TENANT_ID + "/staging/" + DOCUMENT_ID + "/photo.png");

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

    private void stubSuccessfulChunkingAndEmbedding(String markdown) {
        when(chunker.chunk(org.mockito.ArgumentMatchers.eq(markdown), any()))
                .thenReturn(List.of(new ChunkPayload(0, "chunk")));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[768]));
    }

    @Test
    void aSmallImageTriesGeminiFirstAndNeverCallsTheJavaParserOnSuccess() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        stageContent("small image bytes".getBytes());
        when(tier1Extractor.extract(any())).thenReturn(new ExtractionResult("# Photo of a receipt"));
        stubSuccessfulChunkingAndEmbedding("# Photo of a receipt");

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getIngestionTier()).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        verify(tier1Extractor).extract(any());
        verify(tier2Extractor, never()).extract(any());
    }

    @Test
    void aSmallImageFallsBackToOcrWhenGeminiFailsAndTheTierFieldReflectsThat() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        stageContent("small image bytes".getBytes());
        when(tier1Extractor.extract(any())).thenThrow(new DocumentExtractionException("Gemini rejected the image"));
        when(tier2Extractor.extract(any())).thenReturn(new ExtractionResult("OCR text"));
        stubSuccessfulChunkingAndEmbedding("OCR text");

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getIngestionTier()).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        verify(tier1Extractor).extract(any());
        verify(tier2Extractor).extract(any());
    }

    @Test
    void aSmallImageFailingBothTiersProducesACombinedErrorNamingBoth() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        stageContent("small image bytes".getBytes());
        when(tier1Extractor.extract(any())).thenThrow(new DocumentExtractionException("Gemini rejected the image"));
        when(tier2Extractor.extract(any())).thenThrow(new DocumentExtractionException("OCR found no text"));

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage()).contains("Gemini rejected the image").contains("OCR found no text");
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void anImageAtOrOverTheTierOneLimitStillGoesThroughOcrFirstUnchanged() {
        IngestionOrchestrator orchestrator = newOrchestrator();
        byte[] large = new byte[(int) MultimodalLlmExtractor.TIER_1_MAX_BYTES + 1];
        stageContent(large);
        when(tier2Extractor.extract(any())).thenReturn(new ExtractionResult("OCR text from a large scan"));
        stubSuccessfulChunkingAndEmbedding("OCR text from a large scan");

        orchestrator.processDocumentAsync(JOB_ID, DOCUMENT_ID, TENANT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getIngestionTier()).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
        verify(tier1Extractor, never()).extract(any());
    }
}
