package com.synapsemcp.ingestion.extraction;

import com.synapsemcp.document.model.FileCategory;

/** A staged file ready for text extraction. Files are at most 10 MiB, so the content is held in memory. */
public record ExtractionRequest(String filename, FileCategory category, String contentType, byte[] content) {
}
