package com.synapsemcp.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.model.FileCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicChunkerTest {

    @Test
    void rejectsBlankContentBeforeConsultingAnyStrategy() {
        DynamicChunker chunker = new DynamicChunker(List.of());
        assertThatThrownBy(() -> chunker.chunk("   ", FileCategory.TEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsClearlyWhenNoStrategySupportsTheCategory() {
        ChunkingStrategy none = mock(ChunkingStrategy.class);
        when(none.supports(FileCategory.TEXT)).thenReturn(false);
        DynamicChunker chunker = new DynamicChunker(List.of(none));

        assertThatThrownBy(() -> chunker.chunk("some content", FileCategory.TEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEXT");
    }

    @Test
    void dispatchesToTheFirstSupportingStrategy() {
        ChunkingStrategy strategy = mock(ChunkingStrategy.class);
        when(strategy.supports(FileCategory.EXCEL)).thenReturn(true);
        when(strategy.chunk("content", FileCategory.EXCEL)).thenReturn(List.of(new ChunkPayload(0, "content")));
        DynamicChunker chunker = new DynamicChunker(List.of(strategy));

        List<ChunkPayload> result = chunker.chunk("content", FileCategory.EXCEL);

        org.assertj.core.api.Assertions.assertThat(result).hasSize(1);
    }
}
