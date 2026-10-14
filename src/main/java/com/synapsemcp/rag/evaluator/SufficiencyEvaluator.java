package com.synapsemcp.rag.evaluator;

import com.synapsemcp.rag.retrieval.ChunkMatch;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Local context is SUFFICIENT when at least one chunk was retrieved and the best match's cosine
 * similarity is at least 0.70 (design.md section 5.1 / architecture.md ADR-06). Otherwise, the caller
 * falls back to web search.
 */
@Component
public class SufficiencyEvaluator {

    public static final double SIMILARITY_THRESHOLD = 0.70;

    public boolean isSufficient(List<ChunkMatch> matches) {
        return matches.stream().mapToDouble(ChunkMatch::similarityScore).max().orElse(0.0) >= SIMILARITY_THRESHOLD;
    }
}
