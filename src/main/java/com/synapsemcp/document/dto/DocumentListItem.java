package com.synapsemcp.document.dto;

import com.synapsemcp.document.entity.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/** One row of GET /api/v1/documents, per design.md section 7.2. */
public record DocumentListItem(
        UUID id,
        String name,
        String contentType,
        long fileSize,
        DocumentStatus status,
        boolean rawFilePurged,
        long chunkCount,
        Instant createdAt) {
}
