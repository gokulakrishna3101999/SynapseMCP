package com.synapsemcp.ingestion.chunking;

import com.synapsemcp.document.model.FileCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Exactly one chunk per slide (title, bullets, table, speaker notes), per design.md section 4.1 - never
 * split or merged, regardless of the 100-400 token target. {@code PoiPptDocumentParser} always emits a
 * {@code "## Slide N: title"} heading per slide, which is what this splits on.
 */
@Component
public class SlideChunkingStrategy implements ChunkingStrategy {

    private static final Pattern SLIDE_BOUNDARY = Pattern.compile("(?m)(?=^## Slide )");

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.POWERPOINT;
    }

    @Override
    public List<ChunkPayload> chunk(String markdown, FileCategory category) {
        List<String> slides = new ArrayList<>();
        for (String slide : SLIDE_BOUNDARY.split(markdown)) {
            String trimmed = slide.strip();
            if (!trimmed.isEmpty()) {
                slides.add(trimmed);
            }
        }
        if (slides.isEmpty()) {
            slides.add(markdown.strip());
        }
        return ChunkingSupport.toPayloads(slides);
    }
}
