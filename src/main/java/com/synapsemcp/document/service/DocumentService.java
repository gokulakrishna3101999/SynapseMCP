package com.synapsemcp.document.service;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.document.dto.DocumentListItem;
import com.synapsemcp.document.dto.DocumentStatusResponse;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.entity.JobStatus;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.rag.repository.ChunkRepository;
import com.synapsemcp.security.CurrentUser;
import com.synapsemcp.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** Job progress right after acceptance, per architecture.md section 3.2. */
    private static final int QUEUED_PROGRESS = 5;

    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentStorageService storageService;
    private final ChunkRepository chunkRepository;
    private final AuditLogService auditLogService;
    private final RateLimiter rateLimiter;

    public DocumentService(DocumentRepository documentRepository, IngestionJobRepository jobRepository,
                           KnowledgeBaseRepository knowledgeBaseRepository, DocumentStorageService storageService,
                           ChunkRepository chunkRepository, AuditLogService auditLogService, RateLimiter rateLimiter) {
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.storageService = storageService;
        this.chunkRepository = chunkRepository;
        this.auditLogService = auditLogService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Validates, records the document and queued job, and stages the binary in S3. The caller (the
     * controller) triggers async ingestion once this transaction has committed.
     */
    @Transactional
    public UploadResponse upload(MultipartFile file) {
        UUID tenantId = TenantContext.requireTenantId();
        rateLimiter.checkAndConsume(tenantId, "DOCUMENT_UPLOAD", RateLimiter.STRICT_LIMIT, RateLimiter.STRICT_WINDOW);

        if (file.getSize() > MAX_FILE_BYTES) {
            throw fileLimitExceeded();
        }
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Uploaded file is empty.");
        }
        String originalName = file.getOriginalFilename();
        FileCategory.Resolved type = FileCategory.resolve(originalName)
                .orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                        "Unsupported file type. Supported: pdf, doc, docx, xls, xlsx, ppt, pptx, html, htm, "
                                + "txt, csv, md, json, png, jpg, jpeg, webp."));

        KnowledgeBase kb = knowledgeBaseRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " has no knowledge base"));

        Document document = new Document();
        document.setTenantId(tenantId);
        document.setKnowledgeBaseId(kb.getId());
        document.setName(truncate(originalName, 500));
        document.setContentType(type.contentType());
        document.setFileSize(file.getSize());
        // Provisional value: IngestionOrchestrator always tries the Java parser (Tier 2) first for every
        // category and size now, and overwrites this with TIER_1_MULTIMODAL if the Gemini fallback is the
        // one that actually succeeds.
        document.setIngestionTier(IngestionTier.TIER_2_JAVA_PARSER);
        document = documentRepository.saveAndFlush(document);

        IngestionJob job = new IngestionJob();
        job.setTenantId(tenantId);
        job.setDocumentId(document.getId());
        job.setProgressPercent(QUEUED_PROGRESS);
        job = jobRepository.saveAndFlush(job);

        String storagePath = stage(tenantId, document, originalName, file, type.contentType());
        document.setStoragePath(storagePath);
        documentRepository.save(document);

        auditLogService.record(tenantId, CurrentUser.userId(), "DOCUMENT_UPLOAD", "DOCUMENT", document.getId(),
                Map.of("fileName", document.getName(), "fileSize", document.getFileSize()));
        return new UploadResponse(document.getId(), job.getId(), document.getName(), document.getFileSize(),
                document.getIngestionTier(), "QUEUED", false);
    }

    /**
     * Confirms the document belongs to the active tenant before the caller does anything with its id.
     * Explicit transaction boundary: {@code findByIdAndTenantId} is a custom derived query, which
     * doesn't get Spring Data's default per-call transaction - without this, the tenant never gets
     * bound to {@code app.current_tenant_id} and RLS silently returns zero rows.
     */
    @Transactional(readOnly = true)
    public void verifyOwnership(UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        documentRepository.findByIdAndTenantId(documentId, tenantId).orElseThrow(() -> notFound(documentId));
    }

    @Transactional(readOnly = true)
    public DocumentStatusResponse getStatus(UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        Document document = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> notFound(documentId));
        IngestionJob job = jobRepository.findFirstByDocumentIdOrderByCreatedAtDesc(documentId)
                .orElseThrow(() -> notFound(documentId));
        return new DocumentStatusResponse(documentId, document.getStatus(), job.getStatus(), job.getStage(),
                job.getProgressPercent(), job.getErrorMessage());
    }

    @Transactional(readOnly = true)
    public List<DocumentListItem> list() {
        UUID tenantId = TenantContext.requireTenantId();
        return documentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(d -> new DocumentListItem(d.getId(), d.getName(), d.getContentType(), d.getFileSize(),
                        d.getStatus(), d.isRawFilePurged(), chunkRepository.countByDocumentId(d.getId()), d.getCreatedAt()))
                .toList();
    }

    /**
     * Cascade-deletes the document's chunks and job (foreign keys). Rejects a document whose ingestion
     * is still in flight (409) rather than racing {@code IngestionOrchestrator}, which holds its own
     * loaded copy of the row and would otherwise resurrect it (or insert orphaned chunks) via its own
     * later {@code save()} calls. Purges S3 first if the raw file was never purged - true for most
     * documents by the time they reach a terminal state (ADR-07), but not guaranteed: a FAILED document
     * whose own purge-on-failure attempt also failed still has a live staged object.
     */
    @Transactional
    public void delete(UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        Document document = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> notFound(documentId));
        jobRepository.findFirstByDocumentIdOrderByCreatedAtDesc(documentId).ifPresent(job -> {
            if (job.getStatus() != JobStatus.COMPLETED && job.getStatus() != JobStatus.FAILED) {
                throw new ApiException(HttpStatus.CONFLICT, "INGESTION_IN_PROGRESS",
                        "Cannot delete a document while its ingestion job is still " + job.getStatus() + ".");
            }
        });
        if (document.getStoragePath() != null) {
            try {
                storageService.purgeStagedFile(document.getStoragePath());
            } catch (RuntimeException ex) {
                // Best effort, matching IngestionOrchestrator.fail(...): the 24h lifecycle rule is the
                // backstop if even this purge fails, and a storage outage must not block the delete.
                log.error("Failed to purge staged file for document {} on delete", documentId, ex);
            }
        }
        documentRepository.delete(document);
        auditLogService.record(tenantId, CurrentUser.userId(), "DOCUMENT_DELETE", "DOCUMENT", documentId,
                Map.of("fileName", document.getName()));
    }

    private static ApiException notFound(UUID documentId) {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "No document " + documentId);
    }

    private String stage(UUID tenantId, Document document, String originalName, MultipartFile file, String contentType) {
        try (InputStream in = file.getInputStream()) {
            return storageService.stageFile(tenantId, document.getId(), originalName, in, contentType);
        } catch (IOException | RuntimeException ex) {
            // Best effort: S3 may have accepted the object before the failure. The 24h lifecycle rule is the safety net.
            log.error("Failed to stage document {} for tenant {}", document.getId(), tenantId, ex);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STAGING_FAILED",
                    "Could not stage the uploaded file. Please retry.");
        }
    }

    public static ApiException fileLimitExceeded() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_LIMIT_EXCEEDED",
                "Uploaded file exceeds maximum permissible limit of 10MB.");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(value.length() - max);
    }
}
