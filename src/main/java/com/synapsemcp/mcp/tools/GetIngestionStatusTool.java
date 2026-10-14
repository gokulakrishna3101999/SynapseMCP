package com.synapsemcp.mcp.tools;

import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.document.dto.DocumentStatusResponse;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.security.TenantContext;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * {@code get_ingestion_status}: the same status check as {@code GET /api/v1/documents/{id}/status}.
 * Rate-limited here at the tool level, not inside {@code DocumentService.getStatus} itself - that
 * method is shared with the REST polling endpoint (design.md's SSE-fallback, polled every ~1.5s), which
 * must stay unlimited.
 */
@Component
public class GetIngestionStatusTool {

    private final DocumentService documentService;
    private final RateLimiter rateLimiter;

    public GetIngestionStatusTool(DocumentService documentService, RateLimiter rateLimiter) {
        this.documentService = documentService;
        this.rateLimiter = rateLimiter;
    }

    @McpTool(name = "get_ingestion_status",
            description = "Checks the ingestion progress and status of a document previously uploaded by the "
                    + "authenticated user, identified by its document ID.")
    public DocumentStatusResponse status(
            @McpToolParam(description = "The document's UUID, as returned by the upload endpoint", required = true)
            String documentId) {
        rateLimiter.checkAndConsume(TenantContext.requireTenantId(), "GET_INGESTION_STATUS",
                RateLimiter.STRICT_LIMIT, RateLimiter.STRICT_WINDOW);
        return documentService.getStatus(UUID.fromString(documentId));
    }
}
