package com.synapsemcp.rag.websearch;

/** One web result used for the Tavily-replacement fallback (design.md section 5.2 / ADR-06). */
public record WebSearchResult(String title, String url, String content) {
}
