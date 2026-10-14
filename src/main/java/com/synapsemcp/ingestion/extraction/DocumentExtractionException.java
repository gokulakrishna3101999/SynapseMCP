package com.synapsemcp.ingestion.extraction;

/** Extraction failed; the ingestion job is marked FAILED. */
public class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message) {
        super(message);
    }

    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
