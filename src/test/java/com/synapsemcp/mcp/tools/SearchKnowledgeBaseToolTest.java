package com.synapsemcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.rag.correction.QuerySpellCorrector;
import com.synapsemcp.rag.retrieval.ChunkMatch;
import com.synapsemcp.rag.retrieval.VectorRetriever;
import com.synapsemcp.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Regression coverage for a gap found live: {@code search_user_knowledge_base} is pure cosine search with
 * no sufficiency check or web fallback (unlike {@code RagQueryOrchestrator}), so a misspelled query here
 * had no safety net at all - it would simply retrieve badly with nothing to catch it. Proves the query is
 * corrected before it's embedded, exactly like the chat path.
 */
class SearchKnowledgeBaseToolTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final QuerySpellCorrector querySpellCorrector = mock(QuerySpellCorrector.class);
    private final SearchKnowledgeBaseTool tool =
            new SearchKnowledgeBaseTool(embeddingModel, vectorRetriever, new RateLimiter(), querySpellCorrector);

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theCorrectedQueryIsEmbeddedInsteadOfTheRawTypedQuery() {
        when(querySpellCorrector.correct("wat is teh phoenix budgte")).thenReturn("what is the phoenix budget");
        float[] embedding = new float[] {0.1f, 0.2f};
        when(embeddingModel.embed("what is the phoenix budget")).thenReturn(embedding);
        List<ChunkMatch> matches = List.of(new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "doc.txt",
                "budget is 42000", 0.91));
        when(vectorRetriever.search(tenantId, embedding)).thenReturn(matches);

        List<SearchKnowledgeBaseTool.SearchResult> results = tool.search("wat is teh phoenix budgte");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().content()).isEqualTo("budget is 42000");
        verify(embeddingModel).embed("what is the phoenix budget");
    }

    @Test
    void anUncorrectableQueryStillFallsThroughToTheRawQuery() {
        when(querySpellCorrector.correct(anyString())).thenAnswer(inv -> inv.getArgument(0));
        float[] embedding = new float[] {0.1f, 0.2f};
        when(embeddingModel.embed("already correct query")).thenReturn(embedding);
        when(vectorRetriever.search(tenantId, embedding)).thenReturn(List.of());

        tool.search("already correct query");

        verify(embeddingModel).embed("already correct query");
    }
}
