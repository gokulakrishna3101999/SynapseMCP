package com.synapsemcp.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The 3-stage routing decision itself (local -> web -> deterministic failure), with every dependency
 * mocked: this is about branch selection, not any one component's internals (those have their own tests).
 * {@code querySpellCorrector} is stubbed to pass the query through unchanged by default, matching its
 * real best-effort fallback behavior - see {@code QuerySpellCorrectorTest} for its own unit coverage and
 * the tests below that exercise an actual correction.
 */
class RagQueryOrchestratorTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final SufficiencyEvaluator sufficiencyEvaluator = mock(SufficiencyEvaluator.class);
    private final WebSearchService webSearchService = mock(WebSearchService.class);
    private final AnswerSynthesizer answerSynthesizer = mock(AnswerSynthesizer.class);
    private final QuerySpellCorrector querySpellCorrector = mock(QuerySpellCorrector.class);
    private final RagQueryOrchestrator orchestrator = new RagQueryOrchestrator(
            embeddingModel, vectorRetriever, sufficiencyEvaluator, webSearchService, answerSynthesizer,
            new RateLimiter(), querySpellCorrector);

    {
        when(querySpellCorrector.correct(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private final UUID tenantId = UUID.randomUUID();
    private final float[] queryEmbedding = new float[] {0.1f, 0.2f};

    @Test
    void sufficientLocalContextSkipsWebSearchEntirely() {
        List<ChunkMatch> matches = List.of(new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "doc.txt",
                "relevant content", 0.91));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(matches);
        when(sufficiencyEvaluator.isSufficient(matches)).thenReturn(true);
        when(answerSynthesizer.synthesizeFromLocal(eq("what is x?"), eq(matches), eq(List.of()))).thenReturn("x is this.");

        ChatResponse response = orchestrator.answer(tenantId, "what is x?");

        assertThat(response.source()).isEqualTo("LOCAL_KB");
        assertThat(response.answer()).isEqualTo("x is this.");
        assertThat(response.confidenceScore()).isEqualTo(0.91);
        assertThat(response.citations()).hasSize(1);
        assertThat(response.webSources()).isEmpty();
        verify(webSearchService, never()).search(any(), anyInt());
    }

    @Test
    void insufficientLocalContextFallsBackToWebSearch() {
        List<ChunkMatch> matches = List.of();
        List<WebSearchResult> webResults = List.of(new WebSearchResult("Title", "https://example.com", "snippet"));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(matches);
        when(sufficiencyEvaluator.isSufficient(matches)).thenReturn(false);
        when(webSearchService.search("some query", RagQueryOrchestrator.WEB_MAX_RESULTS)).thenReturn(webResults);
        when(answerSynthesizer.synthesizeFromWeb("some query", webResults, List.of())).thenReturn("web-sourced answer");

        ChatResponse response = orchestrator.answer(tenantId, "some query");

        assertThat(response.source()).isEqualTo("WEB_SEARCH");
        assertThat(response.answer()).isEqualTo("web-sourced answer");
        assertThat(response.webSources()).containsExactly(new com.synapsemcp.rag.dto.WebSource("Title", "https://example.com"));
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void bothLocalAndWebInsufficientReturnsTheDeterministicMessageWithoutCallingSynthesis() {
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(List.of());
        when(sufficiencyEvaluator.isSufficient(List.of())).thenReturn(false);
        when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());

        ChatResponse response = orchestrator.answer(tenantId, "nonsense query");

        assertThat(response.source()).isEqualTo("NONE");
        assertThat(response.answer()).isEqualTo(RagQueryOrchestrator.DETERMINISTIC_FAILURE_MESSAGE);
        assertThat(response.confidenceScore()).isEqualTo(0.0);
        assertThat(response.citations()).isEmpty();
        assertThat(response.webSources()).isEmpty();
        verify(answerSynthesizer, never()).synthesizeFromLocal(any(), any(), any());
        verify(answerSynthesizer, never()).synthesizeFromWeb(any(), any(), any());
    }

    /**
     * The real failure this feature fixes: "what about 2025" alone doesn't semantically match anything,
     * but folded together with the prior user turn ("where did gokulakrishna work") it should. Proves the
     * embedded retrieval text actually includes the prior user turn, not just the bare new query.
     */
    @Test
    void aVagueFollowUpIsEnrichedWithRecentUserHistoryBeforeEmbedding() {
        List<ChatTurn> history = List.of(
                new ChatTurn("user", "where did gokulakrishna work"),
                new ChatTurn("assistant", "Gokulakrishna worked at Acme Corp."));
        List<ChunkMatch> matches = List.of(new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "resume.pdf",
                "Acme Corp, 2022-2024", 0.91));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(matches);
        when(sufficiencyEvaluator.isSufficient(matches)).thenReturn(true);
        when(answerSynthesizer.synthesizeFromLocal(any(), any(), any())).thenReturn("In 2022-2024, at Acme Corp.");

        orchestrator.answer(tenantId, "what about 2025", history);

        verify(embeddingModel).embed(eq("where did gokulakrishna work\nwhat about 2025"));
        verify(answerSynthesizer).synthesizeFromLocal(eq("what about 2025"), eq(matches), eq(history));
    }

    @Test
    void historyIsTrimmedToTheMostRecentTurnsOnly() {
        List<ChatTurn> longHistory = List.of(
                new ChatTurn("user", "turn1"), new ChatTurn("assistant", "a1"),
                new ChatTurn("user", "turn2"), new ChatTurn("assistant", "a2"),
                new ChatTurn("user", "turn3"), new ChatTurn("assistant", "a3"),
                new ChatTurn("user", "turn4"), new ChatTurn("assistant", "a4"));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(List.of());
        when(sufficiencyEvaluator.isSufficient(any())).thenReturn(false);
        when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());

        orchestrator.answer(tenantId, "query", longHistory);

        // Only the last 2 user turns (MAX_RETRIEVAL_HISTORY_USER_TURNS) feed the embedded retrieval text.
        verify(embeddingModel).embed(eq("turn3\nturn4\nquery"));
    }

    /**
     * When correction actually changes the query, the corrected text - not the raw user-typed one - must
     * drive retrieval, web search, and synthesis alike, and the correction itself surfaces in the response.
     */
    @Test
    void aCorrectedQueryIsUsedForRetrievalAndSynthesisAndSurfacedInTheResponse() {
        when(querySpellCorrector.correct("wat is teh phoenix budgte")).thenReturn("what is the phoenix budget");
        List<ChunkMatch> matches = List.of(new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "doc.txt",
                "budget is 42000", 0.91));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(matches);
        when(sufficiencyEvaluator.isSufficient(matches)).thenReturn(true);
        when(answerSynthesizer.synthesizeFromLocal(eq("what is the phoenix budget"), eq(matches), eq(List.of())))
                .thenReturn("42000.");

        ChatResponse response = orchestrator.answer(tenantId, "wat is teh phoenix budgte");

        assertThat(response.correctedQuery()).isEqualTo("what is the phoenix budget");
        verify(embeddingModel).embed(eq("what is the phoenix budget"));
    }

    /** Correction leaving the query unchanged (the common case) must not surface a no-op "correction". */
    @Test
    void anUnchangedQueryLeavesCorrectedQueryNullInTheResponse() {
        List<ChunkMatch> matches = List.of(new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "doc.txt",
                "relevant content", 0.91));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(vectorRetriever.search(tenantId, queryEmbedding)).thenReturn(matches);
        when(sufficiencyEvaluator.isSufficient(matches)).thenReturn(true);
        when(answerSynthesizer.synthesizeFromLocal(any(), any(), any())).thenReturn("x is this.");

        ChatResponse response = orchestrator.answer(tenantId, "what is x?");

        assertThat(response.correctedQuery()).isNull();
    }
}
