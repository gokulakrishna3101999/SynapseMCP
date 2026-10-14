package com.synapsemcp.ingestion.chunking;

import com.synapsemcp.document.model.FileCategory;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * One chunk per OCR'd line, for images only (user decision). OCR'd infographics/posters/banners are
 * typically a handful of short, semantically independent facts rather than flowing prose - packing them
 * together toward a word-count target (as {@link HeadingAwareChunkingStrategy} does for every other
 * category) dilutes each fact's embedding with unrelated neighbors, which can push a genuinely relevant
 * fact below the retrieval similarity threshold even though the text was correctly extracted. One chunk
 * per line keeps each fact independently searchable instead.
 */
@Component
public class ImageLineChunkingStrategy implements ChunkingStrategy {

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.IMAGE;
    }

    @Override
    public List<ChunkPayload> chunk(String markdown, FileCategory category) {
        List<String> lines = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            lines.add(markdown.strip());
        }
        return ChunkingSupport.toPayloads(lines);
    }
}
