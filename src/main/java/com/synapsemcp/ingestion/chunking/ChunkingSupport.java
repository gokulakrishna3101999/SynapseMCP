package com.synapsemcp.ingestion.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Word-count-based splitting shared by the category strategies. Word count is a simple proxy for the
 * token ranges in design.md section 4.1 (same imprecision already accepted for Milestone 3.4's placeholder
 * chunker); it is not an actual tokenizer.
 */
final class ChunkingSupport {

    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n\\s*\\n");
    /** Top-level headings only (H1/H2): deeper headings stay inside their enclosing section. */
    private static final Pattern SECTION_BOUNDARY = Pattern.compile("(?m)(?=^#{1,2} )");

    private ChunkingSupport() {
    }

    static int wordCount(String text) {
        return text == null || text.isBlank() ? 0 : text.strip().split("\\s+").length;
    }

    /** Splits on H1/H2 boundaries; with no headings at all, the whole text is one section. */
    static List<String> splitIntoSections(String markdown) {
        List<String> sections = new ArrayList<>();
        for (String section : SECTION_BOUNDARY.split(markdown)) {
            String trimmed = section.strip();
            if (!trimmed.isEmpty()) {
                sections.add(trimmed);
            }
        }
        return sections.isEmpty() ? List.of(markdown.strip()) : sections;
    }

    /**
     * Splits on standalone short ALL-CAPS lines (e.g. "FACT 02 • SENSORY APPARATUS", "SECTION 3"), the
     * closest thing to a heading that plain Tier 2 PDF text extraction has - PDFBox emits no Markdown
     * "#" markers at all. Each such line starts a new section (kept at the start of it, like
     * {@link #splitIntoSections}'s H1/H2 handling). May over-split relative to a document's "real"
     * section boundaries (e.g. an all-caps stat-label row inside one section) - an acceptable tradeoff:
     * extra, still-coherent small sections are far less harmful than merging unrelated facts together.
     */
    static List<String> splitOnCapsHeadings(String text) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            if (looksLikeShortCapsHeading(line) && !current.isEmpty()) {
                sections.add(current.toString().strip());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            sections.add(current.toString().strip());
        }
        return sections.isEmpty() ? List.of(text.strip()) : sections;
    }

    private static boolean looksLikeShortCapsHeading(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.length() > 70) {
            return false;
        }
        long letters = trimmed.chars().filter(Character::isLetter).count();
        if (letters == 0) {
            return false;
        }
        long uppercase = trimmed.chars().filter(Character::isUpperCase).count();
        return uppercase == letters;
    }

    /**
     * Packs sections into chunks up to {@code targetWords}: adjacent small sections are merged together,
     * and any single section bigger than the target is itself split on paragraph boundaries.
     */
    static List<String> packToTarget(List<String> sections, int targetWords) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferWords = 0;
        for (String section : sections) {
            int sectionWords = wordCount(section);
            if (sectionWords > targetWords) {
                if (bufferWords > 0) {
                    chunks.add(buffer.toString().strip());
                    buffer.setLength(0);
                    bufferWords = 0;
                }
                chunks.addAll(chunkByParagraph(section, targetWords));
                continue;
            }
            if (bufferWords > 0 && bufferWords + sectionWords > targetWords) {
                chunks.add(buffer.toString().strip());
                buffer.setLength(0);
                bufferWords = 0;
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(section);
            bufferWords += sectionWords;
        }
        if (bufferWords > 0) {
            chunks.add(buffer.toString().strip());
        }
        return chunks;
    }

    /** Accumulates paragraphs up to the target; a single paragraph over target is hard-split on word boundaries. */
    static List<String> chunkByParagraph(String text, int targetWords) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWords = 0;
        for (String paragraph : PARAGRAPH_BOUNDARY.split(text)) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int paragraphWords = wordCount(trimmed);
            if (paragraphWords > targetWords) {
                List<String> pieces = splitLongParagraph(trimmed, targetWords);
                // Glue any small pending buffer (e.g. a lone heading) onto the first piece instead of
                // isolating it as its own near-useless chunk.
                if (!current.isEmpty()) {
                    pieces.set(0, current.toString().strip() + "\n\n" + pieces.get(0));
                    current.setLength(0);
                    currentWords = 0;
                }
                chunks.addAll(pieces);
                continue;
            }
            if (currentWords > 0 && currentWords + paragraphWords > targetWords) {
                chunks.add(current.toString().strip());
                current.setLength(0);
                currentWords = 0;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(trimmed);
            currentWords += paragraphWords;
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    private static List<String> splitLongParagraph(String paragraph, int targetWords) {
        String[] words = paragraph.split("\\s+");
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int count = 0;
        for (String word : words) {
            if (count == targetWords) {
                parts.add(part.toString());
                part.setLength(0);
                count = 0;
            }
            if (!part.isEmpty()) {
                part.append(' ');
            }
            part.append(word);
            count++;
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        return parts;
    }

    static List<ChunkPayload> toPayloads(List<String> chunks) {
        List<ChunkPayload> payloads = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            payloads.add(new ChunkPayload(i, chunks.get(i)));
        }
        return payloads;
    }
}
