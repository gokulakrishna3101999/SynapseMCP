package com.synapsemcp.ingestion.service;

import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.service.DocumentStorageService;
import com.synapsemcp.document.service.IngestionProgressSseService;
import com.synapsemcp.ingestion.chunking.ChunkPayload;
import com.synapsemcp.ingestion.chunking.DynamicChunker;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.entity.JobStage;
import com.synapsemcp.ingestion.entity.JobStatus;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import com.synapsemcp.ingestion.extraction.JavaParserExtractor;
import com.synapsemcp.ingestion.extraction.MultimodalLlmExtractor;
import com.synapsemcp.ingestion.extraction.Tier2ExtractionProperties;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.entity.Chunk;
import com.synapsemcp.rag.repository.ChunkRepository;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Coordinates one document's ingestion end to end: extraction, chunking, embedding, persistence, S3
 * purge, and SSE progress. Runs on a virtual thread with the uploading tenant already bound (see
 * TenantAwareTaskDecorator), so every repository call below is correctly RLS-scoped.
 * The S3 object is purged on both success and failure (design.md section 4.2 / architecture.md ADR-07).
 * Tier 2 parsing is bounded by {@code synapse.ingestion.tier2.timeout}, mirroring Tier 1's own timeout:
 * a file can be up to 10MiB and a pathological structure can take far longer to parse than its size
 * suggests, and nothing bounded that before this was added.
 * <p>Extraction order (user decision, deviating from the original size-routed plan): the Java parser
 * (Tier 2) is always tried first, for every category and size - it is free and never calls an external
 * API. Only if it fails, and only for files up to Tier 1's 2 MiB limit, is the Gemini multimodal fallback
 * (Tier 1) attempted. If that also fails, or the file is too large for it, the job fails with a combined
 * error message naming both failures. See {@link #extract}.
 */
@Service
@EnableConfigurationProperties(Tier2ExtractionProperties.class)
public class IngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private static final int PARSING_PERCENT = 25;
    private static final int CHUNKING_PERCENT = 50;
    private static final int EMBEDDING_PERCENT = 75;
    private static final int PURGING_PERCENT = 90;
    private static final int COMPLETED_PERCENT = 100;

    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentStorageService storageService;
    private final MultimodalLlmExtractor tier1Extractor;
    private final JavaParserExtractor tier2Extractor;
    private final DynamicChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final IngestionProgressSseService sseService;
    private final Duration tier2Timeout;
    private final ExecutorService tier2Executor = Executors.newVirtualThreadPerTaskExecutor();

    public IngestionOrchestrator(DocumentRepository documentRepository, IngestionJobRepository jobRepository,
                                  ChunkRepository chunkRepository, DocumentStorageService storageService,
                                  MultimodalLlmExtractor tier1Extractor, JavaParserExtractor tier2Extractor,
                                  DynamicChunker chunker, EmbeddingModel embeddingModel,
                                  IngestionProgressSseService sseService, Tier2ExtractionProperties tier2Properties) {
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.chunkRepository = chunkRepository;
        this.storageService = storageService;
        this.tier1Extractor = tier1Extractor;
        this.tier2Extractor = tier2Extractor;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.sseService = sseService;
        this.tier2Timeout = tier2Properties.timeout();
    }

    @PreDestroy
    void shutdown() {
        tier2Executor.shutdownNow();
    }

    @Async("ingestionExecutor")
    public void processDocumentAsync(UUID jobId, UUID documentId, UUID tenantId) {
        Document document = documentRepository.findById(documentId).orElseThrow();
        IngestionJob job = jobRepository.findById(jobId).orElseThrow();
        try {
            FileCategory category = FileCategory.resolve(document.getName())
                    .orElseThrow(() -> new DocumentExtractionException("Unresolvable file category for " + document.getName()))
                    .category();

            updateProgress(job, JobStage.EXTRACTION, PARSING_PERCENT, JobStatus.PARSING);
            sseService.broadcastProgress(documentId, "PARSING", PARSING_PERCENT, "PARSING");
            ExtractionResult extraction = extract(document, category);

            updateProgress(job, JobStage.SPLITTING, CHUNKING_PERCENT, JobStatus.CHUNKING);
            sseService.broadcastProgress(documentId, "CHUNKING", CHUNKING_PERCENT, "CHUNKING");
            List<ChunkPayload> chunkPayloads = chunker.chunk(extraction.markdown(), category);

            updateProgress(job, JobStage.VECTORIZING, EMBEDDING_PERCENT, JobStatus.EMBEDDING);
            sseService.broadcastProgress(documentId, "EMBEDDING", EMBEDDING_PERCENT, "EMBEDDING");
            List<float[]> embeddings = embeddingModel.embed(chunkPayloads.stream().map(ChunkPayload::content).toList());
            chunkRepository.saveAll(buildChunkEntities(document, tenantId, chunkPayloads, embeddings));

            updateProgress(job, JobStage.PURGING_S3, PURGING_PERCENT, JobStatus.PURGING_S3);
            sseService.broadcastProgress(documentId, "PURGING_S3", PURGING_PERCENT, "PURGING_S3");
            storageService.purgeStagedFile(document.getStoragePath());
            document.setStoragePath(null);
            document.setRawFilePurged(true);
            document.setStatus(DocumentStatus.INDEXED);
            documentRepository.save(document);

            updateProgress(job, JobStage.COMPLETED, COMPLETED_PERCENT, JobStatus.COMPLETED);
            sseService.broadcastProgress(documentId, "COMPLETED", COMPLETED_PERCENT, "INDEXED");
            sseService.completeStream(documentId);
        } catch (Exception ex) {
            fail(document, job, ex);
        }
    }

    /**
     * Java parser first, always - it is free and handles every category. Only on failure, and only for
     * files up to the Tier 1 limit, is the Gemini fallback attempted; a file larger than that gets no
     * fallback and fails with the Java parser's own error. {@code document.ingestionTier} is updated here
     * to whichever tier actually produced the result (it was only ever a provisional guess at upload time).
     */
    private ExtractionResult extract(Document document, FileCategory category) throws Exception {
        byte[] content;
        try (InputStream in = storageService.loadStagedFile(document.getStoragePath())) {
            content = in.readAllBytes();
        }
        ExtractionRequest request = new ExtractionRequest(document.getName(), category, document.getContentType(), content);

        DocumentExtractionException tier2Failure;
        try {
            ExtractionResult result = extractTier2WithTimeout(request);
            document.setIngestionTier(IngestionTier.TIER_2_JAVA_PARSER);
            return result;
        } catch (DocumentExtractionException ex) {
            tier2Failure = ex;
        }

        if (content.length > MultimodalLlmExtractor.TIER_1_MAX_BYTES) {
            throw new DocumentExtractionException("Java parser failed: " + tier2Failure.getMessage()
                    + ". LLM fallback not attempted: file exceeds the " + MultimodalLlmExtractor.TIER_1_MAX_BYTES
                    + " byte Tier 1 limit.", tier2Failure);
        }

        try {
            ExtractionResult result = tier1Extractor.extract(request); // already bounded by its own timeout
            document.setIngestionTier(IngestionTier.TIER_1_MULTIMODAL);
            return result;
        } catch (DocumentExtractionException tier1Failure) {
            throw new DocumentExtractionException("Java parser failed: " + tier2Failure.getMessage()
                    + ". LLM fallback also failed: " + tier1Failure.getMessage(), tier1Failure);
        }
    }

    private ExtractionResult extractTier2WithTimeout(ExtractionRequest request) throws Exception {
        Future<ExtractionResult> future = tier2Executor.submit(() -> tier2Extractor.extract(request));
        try {
            return future.get(tier2Timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new DocumentExtractionException(
                    "Tier 2 parsing timed out after " + tier2Timeout + " for " + request.filename(), ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new DocumentExtractionException("Tier 2 parsing failed for " + request.filename(), ex.getCause());
        }
    }

    private static List<Chunk> buildChunkEntities(Document document, UUID tenantId, List<ChunkPayload> chunkPayloads,
                                                    List<float[]> embeddings) {
        List<Chunk> chunks = new ArrayList<>(chunkPayloads.size());
        for (int i = 0; i < chunkPayloads.size(); i++) {
            Chunk chunk = new Chunk();
            chunk.setTenantId(tenantId);
            chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(chunkPayloads.get(i).chunkIndex());
            chunk.setContent(chunkPayloads.get(i).content());
            chunk.setEmbedding(embeddings.get(i));
            chunks.add(chunk);
        }
        return chunks;
    }

    private void fail(Document document, IngestionJob job, Exception ex) {
        log.error("Ingestion failed for document {}", document.getId(), ex);
        try {
            if (document.getStoragePath() != null) {
                storageService.purgeStagedFile(document.getStoragePath());
                document.setStoragePath(null);
            }
        } catch (RuntimeException purgeEx) {
            log.error("Purge-on-failure also failed for document {}", document.getId(), purgeEx);
        }
        String message = errorMessage(ex);
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage(message);
        documentRepository.save(document);

        job.setStatus(JobStatus.FAILED);
        job.setStage(JobStage.FAILED);
        job.setErrorMessage(message);
        jobRepository.save(job);

        sseService.broadcastError(document.getId(), message);
        sseService.completeStream(document.getId());
    }

    private void updateProgress(IngestionJob job, JobStage stage, int percent, JobStatus status) {
        job.setStage(stage);
        job.setProgressPercent(percent);
        job.setStatus(status);
        jobRepository.save(job);
    }

    private static String errorMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
