package com.synapsemcp.document.dto;

import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.ingestion.entity.JobStage;
import com.synapsemcp.ingestion.entity.JobStatus;
import java.util.UUID;

/**
 * GET /api/v1/documents/{id}/status: the polling fallback for clients whose SSE connection drops
 * (plan.md risk table).
 */
public record DocumentStatusResponse(
        UUID documentId,
        DocumentStatus documentStatus,
        JobStatus jobStatus,
        JobStage stage,
        int progressPercent,
        String errorMessage) {
}
