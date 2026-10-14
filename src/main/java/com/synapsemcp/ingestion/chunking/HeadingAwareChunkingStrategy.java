package com.synapsemcp.ingestion.chunking;

import com.synapsemcp.document.model.FileCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Splits on H1/H2 boundaries and packs sections up to a per-category word target (design.md section 4.1):
 * Word/Markdown 400-768 tokens, PDF 512-1024, HTML 400-768, Text/Code 256-512 - target words below are
 * each range's midpoint. Word and HTML parsers emit real Markdown headings, so this genuinely splits by
 * document structure for them. PDFBox's Tier 2 output has no heading markers, so for PDF this degrades
 * to the paragraph-boundary packing in {@link ChunkingSupport#packToTarget} alone - a known limitation
 * (no layout-based heading detection), <strong>except</strong> for PDFs built as many short, independent
 * sections (e.g. a "25 facts about X" compendium, each page its own self-contained fact) - packing those
 * toward a large word target dilutes each fact's embedding with its unrelated neighbors badly enough to
 * push a genuinely relevant fact below the retrieval similarity threshold (found and fixed this way after
 * a real such PDF reproduced exactly that failure). For PDFs, {@link #looksLikeFactSheet} first checks
 * whether splitting on short ALL-CAPS standalone lines (the nearest thing plain-text PDF extraction has
 * to a heading) reveals many small sections; if so each section becomes its own chunk, unpacked. A normal
 * prose-heavy PDF (report, contract) rarely has such lines and falls through to the original packing
 * behavior unchanged. TEXT covers .txt/.csv/.json (no headings) and .md (which may have real ones, passed
 * through verbatim). IMAGE is handled separately by {@link ImageLineChunkingStrategy} (one chunk per
 * OCR'd line, not packed toward a word target - user decision, see its own javadoc).
 */
@Component
public class HeadingAwareChunkingStrategy implements ChunkingStrategy {

    private static final Map<FileCategory, Integer> TARGET_WORDS = Map.of(
            FileCategory.WORD, 580,
            FileCategory.PDF, 760,
            FileCategory.HTML, 580,
            FileCategory.TEXT, 380);

    /** At least this many caps-heading sections, this much smaller than the target on average, to count as a fact sheet. */
    private static final int MIN_FACT_SECTIONS = 5;
    private static final int FACT_SECTION_SIZE_DIVISOR = 3;

    @Override
    public boolean supports(FileCategory category) {
        return TARGET_WORDS.containsKey(category);
    }

    @Override
    public List<ChunkPayload> chunk(String markdown, FileCategory category) {
        int targetWords = TARGET_WORDS.get(category);
        if (category == FileCategory.PDF) {
            List<String> factSections = ChunkingSupport.splitOnCapsHeadings(markdown);
            if (looksLikeFactSheet(factSections, targetWords)) {
                return ChunkingSupport.toPayloads(expandOversized(factSections, targetWords));
            }
        }
        List<String> sections = ChunkingSupport.splitIntoSections(markdown);
        return ChunkingSupport.toPayloads(ChunkingSupport.packToTarget(sections, targetWords));
    }

    private static boolean looksLikeFactSheet(List<String> sections, int targetWords) {
        if (sections.size() < MIN_FACT_SECTIONS) {
            return false;
        }
        int totalWords = sections.stream().mapToInt(ChunkingSupport::wordCount).sum();
        int averageWords = totalWords / sections.size();
        return averageWords < targetWords / FACT_SECTION_SIZE_DIVISOR;
    }

    /** Each detected fact is its own chunk; a section that is itself oversized is still hard-split, same safety net as packToTarget. */
    private static List<String> expandOversized(List<String> sections, int targetWords) {
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (ChunkingSupport.wordCount(section) > targetWords) {
                chunks.addAll(ChunkingSupport.chunkByParagraph(section, targetWords));
            } else {
                chunks.add(section);
            }
        }
        return chunks;
    }
}
