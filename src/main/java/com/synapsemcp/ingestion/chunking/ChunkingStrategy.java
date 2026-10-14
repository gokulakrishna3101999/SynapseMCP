package com.synapsemcp.ingestion.chunking;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;

/** One category-adaptive chunking strategy (plan.md Milestone 4.1), mirroring {@code DocumentParserStrategy}. */
public interface ChunkingStrategy {

    boolean supports(FileCategory category);

    /** @param markdown non-blank extracted content for a document of {@code category} */
    List<ChunkPayload> chunk(String markdown, FileCategory category);
}
