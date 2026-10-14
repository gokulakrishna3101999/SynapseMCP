package com.synapsemcp.ingestion.extraction;

import com.synapsemcp.document.model.FileCategory;

/** Java-side parser for one file category (plan.md Milestone 3.3). */
public interface DocumentParserStrategy {

    boolean supports(FileCategory category);

    ExtractionResult parse(ExtractionRequest request);
}
