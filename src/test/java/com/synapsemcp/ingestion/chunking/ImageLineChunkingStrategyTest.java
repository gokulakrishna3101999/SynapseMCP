package com.synapsemcp.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageLineChunkingStrategyTest {

    private final ImageLineChunkingStrategy strategy = new ImageLineChunkingStrategy();

    @Test
    void supportsOnlyImage() {
        assertThat(strategy.supports(FileCategory.IMAGE)).isTrue();
        assertThat(strategy.supports(FileCategory.PDF)).isFalse();
        assertThat(strategy.supports(FileCategory.TEXT)).isFalse();
    }

    /** The whole point: unrelated short facts must not get packed together into one chunk (dilutes retrieval). */
    @Test
    void eachNonBlankLineBecomesItsOwnChunk() {
        String ocrText = "1. MOST CATS ARE LACTOSE INTOLERANT\n2. AN ADULT CAT HAS 30 TEETH\n9. CATS CAN RUN UP TO 48 KM PER HOUR";

        List<ChunkPayload> chunks = strategy.chunk(ocrText, FileCategory.IMAGE);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).isEqualTo("1. MOST CATS ARE LACTOSE INTOLERANT");
        assertThat(chunks.get(1).content()).isEqualTo("2. AN ADULT CAT HAS 30 TEETH");
        assertThat(chunks.get(2).content()).isEqualTo("9. CATS CAN RUN UP TO 48 KM PER HOUR");
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(2).chunkIndex()).isEqualTo(2);
    }

    @Test
    void blankLinesAreSkipped() {
        String ocrText = "First fact\n\n\nSecond fact\n   \nThird fact";

        List<ChunkPayload> chunks = strategy.chunk(ocrText, FileCategory.IMAGE);

        assertThat(chunks).extracting(ChunkPayload::content).containsExactly("First fact", "Second fact", "Third fact");
    }

    @Test
    void aSingleLineOfTextIsOneChunk() {
        List<ChunkPayload> chunks = strategy.chunk("Just one line of OCR'd text", FileCategory.IMAGE);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("Just one line of OCR'd text");
    }
}
