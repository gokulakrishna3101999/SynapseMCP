package com.synapsemcp.ingestion.extraction;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tier 2 entry point: picks the {@link DocumentParserStrategy} for the file's category and guarantees that a
 * successful result is never blank. Every failure surfaces as a {@link DocumentExtractionException}.
 */
@Component
public class JavaParserExtractor {

    private final List<DocumentParserStrategy> parsers;

    public JavaParserExtractor(List<DocumentParserStrategy> parsers) {
        this.parsers = parsers;
    }

    public ExtractionResult extract(ExtractionRequest request) {
        if (request.content() == null || request.content().length == 0) {
            throw new DocumentExtractionException("Cannot extract from an empty file: " + request.filename());
        }
        DocumentParserStrategy parser = parsers.stream()
                .filter(p -> p.supports(request.category()))
                .findFirst()
                .orElseThrow(() -> new DocumentExtractionException(
                        "No parser available for " + request.category() + ": " + request.filename()));
        ExtractionResult result;
        try {
            result = parser.parse(request);
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (RuntimeException | LinkageError ex) {
            throw new DocumentExtractionException("Parsing failed for " + request.filename() + ": " + ex.getMessage(), ex);
        }
        if (result == null || result.markdown() == null || result.markdown().isBlank()) {
            throw new DocumentExtractionException("No readable text found in " + request.filename());
        }
        return result;
    }
}
