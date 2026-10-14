package com.synapsemcp.ingestion.chunking;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Routes to the category-adaptive {@link ChunkingStrategy} for the document's {@link FileCategory}
 * (design.md section 4.1 / Milestone 4.1), mirroring {@code JavaParserExtractor}'s dispatch pattern.
 */
@Component
public class DynamicChunker {

    private final List<ChunkingStrategy> strategies;

    public DynamicChunker(List<ChunkingStrategy> strategies) {
        this.strategies = strategies;
    }

    public List<ChunkPayload> chunk(String markdown, FileCategory category) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("Cannot chunk blank extracted content");
        }
        ChunkingStrategy strategy = strategies.stream()
                .filter(s -> s.supports(category))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No chunking strategy for category " + category));
        List<ChunkPayload> chunks = strategy.chunk(markdown, category);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Cannot chunk blank extracted content");
        }
        return chunks;
    }
}
