package com.synapsemcp.document.controller;

import com.synapsemcp.document.dto.DocumentListItem;
import com.synapsemcp.document.dto.DocumentStatusResponse;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.document.service.IngestionProgressSseService;
import com.synapsemcp.ingestion.service.IngestionOrchestrator;
import com.synapsemcp.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final IngestionOrchestrator orchestrator;
    private final IngestionProgressSseService sseService;

    public DocumentController(DocumentService documentService, IngestionOrchestrator orchestrator,
                               IngestionProgressSseService sseService) {
        this.documentService = documentService;
        this.orchestrator = orchestrator;
        this.sseService = sseService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadResponse upload(@RequestPart("file") MultipartFile file) {
        UUID tenantId = TenantContext.requireTenantId();
        UploadResponse response = documentService.upload(file);
        orchestrator.processDocumentAsync(response.jobId(), response.documentId(), tenantId);
        return response;
    }

    @GetMapping(value = "/{id}/progress-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progressStream(@PathVariable UUID id) {
        documentService.verifyOwnership(id);
        return sseService.registerClient(id);
    }

    @GetMapping("/{id}/status")
    public DocumentStatusResponse status(@PathVariable UUID id) {
        return documentService.getStatus(id);
    }

    @GetMapping
    public List<DocumentListItem> list() {
        return documentService.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        documentService.delete(id);
    }
}
