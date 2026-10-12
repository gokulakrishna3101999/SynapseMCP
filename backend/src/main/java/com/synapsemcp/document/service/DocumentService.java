package com.synapsemcp.document.service;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.ingestion.entity.IngestionJob;
import com.synapsemcp.ingestion.extraction.IngestionTierRouter;
import com.synapsemcp.ingestion.repository.IngestionJobRepository;
import com.synapsemcp.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
    private final IngestionTierRouter tierRouter;

    public DocumentService(DocumentRepository documentRepository, IngestionJobRepository jobRepository,
                           KnowledgeBaseRepository knowledgeBaseRepository, DocumentStorageService storageService,
                           IngestionTierRouter tierRouter) {
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.storageService = storageService;
        this.tierRouter = tierRouter;
    }

    /**
     * Validates, records the document and queued job, and stages the binary in S3. Processing is triggered in
     * Milestone 3.4. If staging fails the transaction rolls back, so no document row is left behind.
     */
    @Transactional
    public UploadResponse upload(MultipartFile file) {
        UUID tenantId = TenantContext.requireTenantId();

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
        document.setIngestionTier(tierRouter.route(type.category(), file.getSize()));
        document = documentRepository.saveAndFlush(document);

        IngestionJob job = new IngestionJob();
        job.setTenantId(tenantId);
        job.setDocumentId(document.getId());
        job.setProgressPercent(QUEUED_PROGRESS);
        job = jobRepository.saveAndFlush(job);

        String storagePath = stage(tenantId, document, originalName, file, type.contentType());
        document.setStoragePath(storagePath);
        documentRepository.save(document);

        return new UploadResponse(document.getId(), job.getId(), document.getName(), document.getFileSize(),
                document.getIngestionTier(), "QUEUED", false);
    }

    private String stage(UUID tenantId, Document document, String originalName, MultipartFile file, String contentType) {
        try (InputStream in = file.getInputStream()) {
            return storageService.stageFile(tenantId, document.getId(), originalName, in, file.getSize(), contentType);
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
