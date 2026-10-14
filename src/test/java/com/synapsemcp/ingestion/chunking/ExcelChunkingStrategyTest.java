package com.synapsemcp.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExcelChunkingStrategyTest {

    private final ExcelChunkingStrategy strategy = new ExcelChunkingStrategy();

    @Test
    void supportsOnlyExcel() {
        assertThat(strategy.supports(FileCategory.EXCEL)).isTrue();
        assertThat(strategy.supports(FileCategory.WORD)).isFalse();
    }

    @Test
    void smallSheetBecomesOneChunkWithHeaderAndSeparator() {
        String markdown = "## Budget\n\n"
                + "| Team | Cost |\n"
                + "| --- | --- |\n"
                + "| Platform | 4200 |\n"
                + "| Growth | 1800 |\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.EXCEL);

        assertThat(chunks).hasSize(1);
        String content = chunks.get(0).content();
        assertThat(content).startsWith("## Budget");
        assertThat(content).contains("| Team | Cost |");
        assertThat(content).contains("| Platform | 4200 |");
        assertThat(content).contains("| Growth | 1800 |");
    }

    @Test
    void largeSheetSplitsOnRowBoundariesWithHeaderInjectedIntoEveryChunk() {
        StringBuilder markdown = new StringBuilder("## Transactions\n\n| Id | Description |\n| --- | --- |\n");
        // Each row is a handful of words; force many rows well past TARGET_WORDS (256) so it must split.
        String longDescription = IntStream.range(0, 30).mapToObj(i -> "word" + i)
                .collect(Collectors.joining(" "));
        for (int i = 0; i < 20; i++) {
            markdown.append("| ").append(i).append(" | ").append(longDescription).append(" |\n");
        }

        List<ChunkPayload> chunks = strategy.chunk(markdown.toString(), FileCategory.EXCEL);

        assertThat(chunks.size()).isGreaterThan(1);
        for (ChunkPayload chunk : chunks) {
            assertThat(chunk.content()).as("every chunk carries the sheet heading").startsWith("## Transactions");
            assertThat(chunk.content()).as("every chunk carries the header row").contains("| Id | Description |");
            assertThat(chunk.content()).as("every chunk carries the separator row").contains("| --- | --- |");
        }
        // No row's identity (its index cell) is lost or duplicated across chunk boundaries.
        String allContent = chunks.stream().map(ChunkPayload::content).collect(Collectors.joining("\n"));
        for (int i = 0; i < 20; i++) {
            assertThat(allContent).contains("| " + i + " | ");
        }
    }

    @Test
    void multipleSheetsEachChunkedIndependently() {
        String markdown = "## Sheet1\n\n| A |\n| --- |\n| 1 |\n"
                + "## Sheet2\n\n| B |\n| --- |\n| 2 |\n";

        List<ChunkPayload> chunks = strategy.chunk(markdown, FileCategory.EXCEL);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).startsWith("## Sheet1");
        assertThat(chunks.get(1).content()).startsWith("## Sheet2");
    }
}
