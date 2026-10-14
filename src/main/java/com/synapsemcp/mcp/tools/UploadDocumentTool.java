package com.synapsemcp.mcp.tools;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.ingestion.service.IngestionOrchestrator;
import com.synapsemcp.security.TenantContext;
import java.util.Base64;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code upload_document}: lets an external MCP client add a file to the authenticated user's own
 * knowledge base, not just query one. Decodes the Base64 payload into a {@link ByteArrayMultipartFile}
 * and hands it to the exact same {@link DocumentService#upload} + {@link IngestionOrchestrator} pipeline
 * {@code POST /api/v1/documents/upload} uses - same validation (type/size/empty), same rate limiting, same
 * staging/parsing/chunking/embedding, same audit log entry. An MCP client never diverges from the web
 * app's upload behavior. This is async like the REST endpoint: the call returns as soon as the job is
 * queued, not once ingestion finishes - the caller should poll {@code get_ingestion_status} (or the status
 * returned here) before relying on {@code query_user_rag}/{@code search_user_knowledge_base} to find it.
 */
@Component
public class UploadDocumentTool {

    private final DocumentService documentService;
    private final IngestionOrchestrator orchestrator;

    public UploadDocumentTool(DocumentService documentService, IngestionOrchestrator orchestrator) {
        this.documentService = documentService;
        this.orchestrator = orchestrator;
    }

    @McpTool(name = "upload_document",
            description = "Uploads a file into the authenticated user's own knowledge base for ingestion - the "
                    + "same pipeline (parsing, chunking, embedding) as the web app's upload. Returns immediately "
                    + "with a document ID and QUEUED status; call get_ingestion_status to confirm it reaches "
                    + "INDEXED before expecting query_user_rag or search_user_knowledge_base to find its content. "
                    + "Supported types: pdf, doc, docx, xls, xlsx, ppt, pptx, html, htm, txt, csv, md, json, png, "
                    + "jpg, jpeg, webp. Maximum file size 10MB.")
    public UploadResponse upload(
            @McpToolParam(description = "The original filename including its extension, e.g. \"report.pdf\"",
                    required = true) String filename,
            @McpToolParam(description = "The file's raw bytes, Base64-encoded", required = true) String contentBase64) {
        UUID tenantId = TenantContext.requireTenantId();
        byte[] bytes = decode(contentBase64);
        MultipartFile file = new ByteArrayMultipartFile(filename, bytes);
        UploadResponse response = documentService.upload(file);
        orchestrator.processDocumentAsync(response.jobId(), response.documentId(), tenantId);
        return response;
    }

    private static byte[] decode(String contentBase64) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_CONTENT",
                    "contentBase64 is not valid Base64: " + ex.getMessage());
        }
    }
}
