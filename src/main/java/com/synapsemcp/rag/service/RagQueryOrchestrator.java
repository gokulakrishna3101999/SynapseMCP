package com.synapsemcp.rag.service;

import com.synapsemcp.common.ratelimit.RateLimiter;
import com.synapsemcp.rag.correction.QuerySpellCorrector;
import com.synapsemcp.rag.dto.ChatResponse;
import com.synapsemcp.rag.dto.ChatTurn;
import com.synapsemcp.rag.evaluator.SufficiencyEvaluator;
import com.synapsemcp.rag.retrieval.ChunkMatch;
import com.synapsemcp.rag.retrieval.VectorRetriever;
import com.synapsemcp.rag.synthesis.AnswerSynthesizer;
import com.synapsemcp.rag.websearch.WebSearchResult;
import com.synapsemcp.rag.websearch.WebSearchService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * The 3-stage query routing pipeline (design.md section 5.1, architecture.md ADR-06): local vector
 * search first; web search fallback only when local context is insufficient; a deterministic failure
 * message when both come up empty. Never silently falls through to a model's own unguarded knowledge.
 * Shared by both {@code POST /api/v1/chat} and the {@code query_user_rag} MCP tool, so they naturally
 * share one rate-limit bucket per tenant too.
 * <p>Conversation memory (user decision): the client holds and sends its own recent history - never
 * persisted here, used only in-memory for this one request, trimmed further below. Two uses: (1) the
 * last couple of user turns are folded into the text that gets embedded for vector search, so a vague
 * follow-up like "what about 2025" can still retrieve the right chunks when the prior turn was about
 * someone's work history; (2) the full trimmed history is handed to {@link AnswerSynthesizer} so the
 * final answer can read naturally as a follow-up - the synthesis prompt still grounds facts strictly in
 * the retrieved excerpts, history is for continuity only, never a source of new facts.
 * <p>Spelling correction (user decision): retrieval here is pure cosine similarity over embeddings, with
 * no lexical/keyword fallback, so a misspelled query can embed too far from the correct one to clear
 * {@link SufficiencyEvaluator}'s threshold even when the right chunks exist. {@link QuerySpellCorrector}
 * runs once per request, before the query is folded into the retrieval embedding text; its output (or the
 * original query, unchanged, if correction made no change or failed) is then used for retrieval, web
 * search, and answer synthesis alike, so the whole pipeline reasons about one consistent query. The raw
 * user-typed query is preserved only to detect whether a correction actually happened, for
 * {@link ChatResponse#correctedQuery()}.
 */
@Service
public class RagQueryOrchestrator {

    static final int WEB_MAX_RESULTS = 5;
    static final int MAX_HISTORY_TURNS = 6;
    static final int MAX_RETRIEVAL_HISTORY_USER_TURNS = 2;
    static final String DETERMINISTIC_FAILURE_MESSAGE =
            "No relevant data found in your knowledge base, and fallback web search returned no matching results.";

    private final EmbeddingModel embeddingModel;
    private final VectorRetriever vectorRetriever;
    private final SufficiencyEvaluator sufficiencyEvaluator;
    private final WebSearchService webSearchService;
    private final AnswerSynthesizer answerSynthesizer;
    private final RateLimiter rateLimiter;
    private final QuerySpellCorrector querySpellCorrector;

    public RagQueryOrchestrator(EmbeddingModel embeddingModel, VectorRetriever vectorRetriever,
                                 SufficiencyEvaluator sufficiencyEvaluator, WebSearchService webSearchService,
                                 AnswerSynthesizer answerSynthesizer, RateLimiter rateLimiter,
                                 QuerySpellCorrector querySpellCorrector) {
        this.embeddingModel = embeddingModel;
        this.vectorRetriever = vectorRetriever;
        this.sufficiencyEvaluator = sufficiencyEvaluator;
        this.webSearchService = webSearchService;
        this.answerSynthesizer = answerSynthesizer;
        this.rateLimiter = rateLimiter;
        this.querySpellCorrector = querySpellCorrector;
    }

    public ChatResponse answer(UUID tenantId, String query) {
        return answer(tenantId, query, List.of());
    }

    public ChatResponse answer(UUID tenantId, String query, List<ChatTurn> history) {
        rateLimiter.checkAndConsume(tenantId, "CHAT", RateLimiter.STRICT_LIMIT, RateLimiter.STRICT_WINDOW);
        List<ChatTurn> trimmedHistory = trim(history);

        String correctedQuery = querySpellCorrector.correct(query);
        String correctionForResponse = Objects.equals(query, correctedQuery) ? null : correctedQuery;

        float[] queryEmbedding = embeddingModel.embed(retrievalQuery(correctedQuery, trimmedHistory));
        List<ChunkMatch> matches = vectorRetriever.search(tenantId, queryEmbedding);

        if (sufficiencyEvaluator.isSufficient(matches)) {
            String answer = answerSynthesizer.synthesizeFromLocal(correctedQuery, matches, trimmedHistory);
            return ChatResponse.localKb(answer, matches, correctionForResponse);
        }

        List<WebSearchResult> webResults = webSearchService.search(correctedQuery, WEB_MAX_RESULTS);
        if (!webResults.isEmpty()) {
            String answer = answerSynthesizer.synthesizeFromWeb(correctedQuery, webResults, trimmedHistory);
            return ChatResponse.webSearch(answer, webResults, correctionForResponse);
        }

        return ChatResponse.none(DETERMINISTIC_FAILURE_MESSAGE);
    }

    private static List<ChatTurn> trim(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - MAX_HISTORY_TURNS);
        return history.subList(from, history.size());
    }

    /**
     * Folds the last couple of user turns into the text embedded for vector search, so a vague
     * follow-up question can still retrieve the chunks a reasonable person would expect given the
     * conversation so far. Only user turns are used here (not assistant answers) to keep the embedded
     * text query-like rather than diluted with prose.
     */
    private static String retrievalQuery(String query, List<ChatTurn> trimmedHistory) {
        List<String> recentUserTurns = trimmedHistory.stream()
                .filter(t -> "user".equals(t.role()))
                .map(ChatTurn::text)
                .toList();
        int from = Math.max(0, recentUserTurns.size() - MAX_RETRIEVAL_HISTORY_USER_TURNS);
        List<String> contextTurns = recentUserTurns.subList(from, recentUserTurns.size());
        if (contextTurns.isEmpty()) {
            return query;
        }
        return String.join("\n", contextTurns) + "\n" + query;
    }
}
