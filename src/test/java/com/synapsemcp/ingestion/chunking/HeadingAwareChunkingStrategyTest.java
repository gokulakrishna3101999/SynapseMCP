package com.synapsemcp.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HeadingAwareChunkingStrategyTest {

    private final HeadingAwareChunkingStrategy strategy = new HeadingAwareChunkingStrategy();

    @Test
    void supportsWordPdfHtmlAndTextOnly() {
        assertThat(strategy.supports(FileCategory.WORD)).isTrue();
        assertThat(strategy.supports(FileCategory.PDF)).isTrue();
        assertThat(strategy.supports(FileCategory.HTML)).isTrue();
        assertThat(strategy.supports(FileCategory.TEXT)).isTrue();
        assertThat(strategy.supports(FileCategory.IMAGE)).isFalse();
        assertThat(strategy.supports(FileCategory.EXCEL)).isFalse();
        assertThat(strategy.supports(FileCategory.POWERPOINT)).isFalse();
    }

    @Test
    void smallHeadedDocumentMergesSectionsIntoOneChunk() {
        String markdown = "# Intro\n\nShort intro paragraph.\n\n## Details\n\nShort details paragraph.\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.WORD);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("Intro", "Details");
    }

    @Test
    void largeDocumentSplitsAtHeadingBoundariesNotMidSection() {
        String bigSection = IntStream.range(0, 700).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
        String markdown = "# First\n\n" + bigSection + "\n\n# Second\n\nShort second section.\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.WORD);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).content()).startsWith("# First");
        assertThat(chunks.stream().map(ChunkPayload::content)).anySatisfy(c -> assertThat(c).contains("Second"));
    }

    @Test
    void noHeadingsAtAllFallsBackToParagraphPacking() {
        // PDFBox/OCR output has no "#" headings at all - must still chunk sensibly, not throw.
        String markdown = "Paragraph one.\n\nParagraph two.\n\nParagraph three.\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.PDF);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("Paragraph one.", "Paragraph two.", "Paragraph three.");
    }

    /**
     * Reproduces the real failure this fixes: a PDF compendium of many short, independent facts (like
     * "The Secret Life of Felines.pdf" - 25 one-page facts, each starting "FACT NN • CATEGORY") must not
     * have unrelated facts packed into one chunk, or a genuinely relevant fact's embedding gets diluted
     * below the retrieval threshold even though the text was correctly extracted.
     */
    @Test
    void factSheetStylePdfKeepsEachFactAsItsOwnChunkInsteadOfPackingThemTogether() {
        StringBuilder markdown = new StringBuilder();
        String[] categories = {"EVOLUTIONARY ROOTS", "SENSORY APPARATUS", "BIO-ACOUSTIC HEALING",
                "BIOMECHANICS", "AERIAL DYNAMICS", "OCULAR ADAPTATIONS"};
        for (int i = 0; i < categories.length; i++) {
            markdown.append("FACT 0").append(i + 1).append(" • ").append(categories[i]).append('\n');
            markdown.append("Some short title for fact ").append(i + 1).append('\n');
            markdown.append("A short paragraph of body text describing fact number ").append(i + 1)
                    .append(" in reasonable detail for a test fixture.\n\n");
        }

        List<ChunkPayload> chunks = strategy.chunk(markdown.toString(), FileCategory.PDF);

        assertThat(chunks).hasSize(categories.length);
        for (int i = 0; i < categories.length; i++) {
            assertThat(chunks.get(i).content()).as("chunk %d", i)
                    .contains("FACT 0" + (i + 1)).contains(categories[i])
                    .doesNotContain(categories[(i + 1) % categories.length]);
        }
    }

    @Test
    void aFewIsolatedCapsLinesInAProseHeavyPdfDoesNotTriggerFactSheetMode() {
        // Only one short caps-like line (e.g. a "NOTE:" callout) in an otherwise normal prose PDF -
        // must not be mistaken for a fact sheet and over-fragmented.
        String bigParagraph = IntStream.range(0, 700).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
        String markdown = "IMPORTANT NOTICE\n\n" + bigParagraph;

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.PDF);

        assertThat(chunks.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void textCategoryUsesASmallerTargetThanPdf() {
        // Same raw text, different category -> TEXT's lower word target should split sooner than PDF's.
        String paragraph1 = IntStream.range(0, 350).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        String paragraph2 = IntStream.range(0, 350).mapToObj(i -> "x" + i).collect(Collectors.joining(" "));
        String markdown = paragraph1 + "\n\n" + paragraph2;

        List<ChunkPayload> pdfChunks = strategy.chunk(markdown, FileCategory.PDF);
        List<ChunkPayload> textChunks = strategy.chunk(markdown, FileCategory.TEXT);

        assertThat(pdfChunks).hasSize(1);
        assertThat(textChunks.size()).isGreaterThan(pdfChunks.size());
    }
}
