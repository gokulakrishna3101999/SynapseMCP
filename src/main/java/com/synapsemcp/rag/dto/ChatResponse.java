package com.synapsemcp.rag.dto;

import com.synapsemcp.rag.retrieval.ChunkMatch;
import com.synapsemcp.rag.websearch.WebSearchResult;
import java.util.List;

/**
 * POST /api/v1/chat response, per design.md section 7.3/5.2. {@code source} is one of {@code LOCAL_KB},
 * {@code WEB_SEARCH}, or {@code NONE} (architecture.md ADR-06). {@code correctedQuery} is non-null only
 * when {@code QuerySpellCorrector} actually changed the user's query (spelling/typo correction before
 * retrieval) - null means either correction left it unchanged or correction itself failed and the
 * original query was used, so clients should treat null as "nothing to show the user here", not as
 * "correction didn't run".
 */
public record ChatResponse(String answer, String source, double confidenceScore, List<Citation> citations,
                            List<WebSource> webSources, String correctedQuery) {

    private static final int SNIPPET_MAX_CHARS = 280;

    public static ChatResponse localKb(String answer, List<ChunkMatch> matches, String correctedQuery) {
        List<Citation> citations = matches.stream()
                .map(m -> new Citation(m.documentId(), m.documentName(), m.chunkId(), snippet(m.content()),
                        m.similarityScore()))
                .toList();
        double confidence = matches.stream().mapToDouble(ChunkMatch::similarityScore).max().orElse(0.0);
        return new ChatResponse(answer, "LOCAL_KB", confidence, citations, List.of(), correctedQuery);
    }

    public static ChatResponse webSearch(String answer, List<WebSearchResult> results, String correctedQuery) {
        List<WebSource> sources = results.stream().map(r -> new WebSource(r.title(), r.url())).toList();
        return new ChatResponse(answer, "WEB_SEARCH", 0.0, List.of(), sources, correctedQuery);
    }

    public static ChatResponse none(String deterministicMessage) {
        return new ChatResponse(deterministicMessage, "NONE", 0.0, List.of(), List.of(), null);
    }

    private static String snippet(String content) {
        return content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS) + "...";
    }
}
