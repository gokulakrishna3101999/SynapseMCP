package com.synapsemcp.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SlideChunkingStrategyTest {

    private final SlideChunkingStrategy strategy = new SlideChunkingStrategy();

    @Test
    void supportsOnlyPowerPoint() {
        assertThat(strategy.supports(FileCategory.POWERPOINT)).isTrue();
        assertThat(strategy.supports(FileCategory.EXCEL)).isFalse();
    }

    @Test
    void oneChunkPerSlideInOrder() {
        String markdown = "## Slide 1: Roadmap\n\n- Launch in Q3\n- Hire two engineers\n\n**Notes:** Speaker note\n\n"
                + "## Slide 2: Budget\n\n| Team | Cost |\n| --- | --- |\n| Platform | 4200 |\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.POWERPOINT);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(0).content()).startsWith("## Slide 1: Roadmap").contains("Hire two engineers", "Speaker note");
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
        assertThat(chunks.get(1).content()).startsWith("## Slide 2: Budget").contains("Platform");
    }

    @Test
    void slideNeverSplitEvenWhenLargerThanTheTokenTarget() {
        // 100-400 tokens is the design.md target, but a slide packed with text must still be ONE chunk.
        String bulletBlock = IntStream.range(0, 600).mapToObj(i -> "- bullet " + i)
                .collect(Collectors.joining("\n"));
        String markdown = "## Slide 1: Dense\n\n" + bulletBlock + "\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.POWERPOINT);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("bullet 0", "bullet 599");
    }

    @Test
    void slideWithNoTitleOnlyNumbering() {
        String markdown = "## Slide 1\n\nUntitled content\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.POWERPOINT);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("Untitled content");
    }
}
