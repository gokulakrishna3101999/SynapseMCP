package com.synapsemcp.rag.evaluator;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.rag.retrieval.ChunkMatch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SufficiencyEvaluatorTest {

    private final SufficiencyEvaluator evaluator = new SufficiencyEvaluator();

    private static ChunkMatch matchWithScore(double score) {
        return new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "doc.txt", "content", score);
    }

    @Test
    void zeroChunksIsInsufficient() {
        assertThat(evaluator.isSufficient(List.of())).isFalse();
    }

    @Test
    void belowThresholdIsInsufficient() {
        assertThat(evaluator.isSufficient(List.of(matchWithScore(0.69)))).isFalse();
    }

    @Test
    void exactlyAtThresholdIsSufficient() {
        assertThat(evaluator.isSufficient(List.of(matchWithScore(0.70)))).isTrue();
    }

    @Test
    void aboveThresholdIsSufficient() {
        assertThat(evaluator.isSufficient(List.of(matchWithScore(0.95)))).isTrue();
    }

    @Test
    void usesTheBestMatchAmongSeveral() {
        List<ChunkMatch> matches = List.of(matchWithScore(0.40), matchWithScore(0.71), matchWithScore(0.55));
        assertThat(evaluator.isSufficient(matches)).isTrue();
    }
}
