package com.synapsemcp.ingestion.extraction;

/** Clean structured Markdown produced by either ingestion tier; the chunker consumes this. */
public record ExtractionResult(String markdown) {
}
