package com.synapsemcp.ingestion.extraction;

import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.model.FileCategory;
import org.springframework.stereotype.Component;

/**
 * Tier 1 (Gemini multimodal): PDFs and images of at most 2 MiB.
 * Tier 2 (Java parsers): everything else, including every other file type regardless of size.
 */
@Component
public class IngestionTierRouter {

    public static final long TIER_1_MAX_BYTES = 2L * 1024 * 1024;

    public IngestionTier route(FileCategory category, long sizeBytes) {
        boolean multimodal = category == FileCategory.PDF || category == FileCategory.IMAGE;
        return multimodal && sizeBytes <= TIER_1_MAX_BYTES
                ? IngestionTier.TIER_1_MULTIMODAL
                : IngestionTier.TIER_2_JAVA_PARSER;
    }
}
