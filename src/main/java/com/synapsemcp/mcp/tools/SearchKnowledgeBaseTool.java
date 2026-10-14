package com.synapsemcp.mcp.tools;

import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.rag.correction.QuerySpellCorrector;
import com.synapsemcp.rag.retrieval.ChunkMatch;
import com.synapsemcp.rag.retrieval.VectorRetriever;
import com.synapsemcp.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * {@code search_user_knowledge_base}: plain semantic search, no synthesis (architecture.md section 6.2).
 * Like {@link com.synapsemcp.rag.service.RagQueryOrchestrator}, this is pure cosine similarity with no
 * lexical fallback, so the query is spell-corrected first for the same reason (a misspelled query can
 * embed too far from the correct one to match well) - see {@link QuerySpellCorrector}.
 */
@Component
public class SearchKnowledgeBaseTool {

    private final EmbeddingModel embeddingModel;
    private final VectorRetriever vectorRetriever;
    private final RateLimiter rateLimiter;
    private final QuerySpellCorrector querySpellCorrector;

    public SearchKnowledgeBaseTool(EmbeddingModel embeddingModel, VectorRetriever vectorRetriever,
                                    RateLimiter rateLimiter, QuerySpellCorrector querySpellCorrector) {
        this.embeddingModel = embeddingModel;
        this.vectorRetriever = vectorRetriever;
        this.rateLimiter = rateLimiter;
        this.querySpellCorrector = querySpellCorrector;
    }

    @McpTool(name = "search_user_knowledge_base",
            description = "Semantic search over the authenticated user's own knowledge base. Returns the top "
                    + "matching chunks with their document name and cosine similarity score; does not generate "
                    + "an answer (use query_user_rag for that).")
    public List<SearchResult> search(
            @McpToolParam(description = "The search query text", required = true) String query) {
        UUID tenantId = TenantContext.requireTenantId();
        rateLimiter.checkAndConsume(tenantId, "SEARCH_KB", RateLimiter.STRICT_LIMIT, RateLimiter.STRICT_WINDOW);
        String correctedQuery = querySpellCorrector.correct(query);
        float[] queryEmbedding = embeddingModel.embed(correctedQuery);
        return vectorRetriever.search(tenantId, queryEmbedding).stream()
                .map(SearchKnowledgeBaseTool::toSearchResult)
                .toList();
    }

    private static SearchResult toSearchResult(ChunkMatch match) {
        return new SearchResult(match.documentId(), match.documentName(), match.content(), match.similarityScore());
    }

    public record SearchResult(UUID documentId, String documentName, String content, double similarityScore) {
    }
}
