package com.synapsemcp.document.dto;

import com.synapsemcp.document.entity.IngestionTier;

import java.util.UUID;

public record UploadResponse(
        UUID documentId,
        UUID jobId,
        String fileName,
        long fileSize,
        IngestionTier ingestionTier,
        String status,
        boolean rawFilePurged) {
}
