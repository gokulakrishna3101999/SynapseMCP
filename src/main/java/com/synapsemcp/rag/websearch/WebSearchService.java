package com.synapsemcp.rag.websearch;

import java.util.List;

/**
 * Web search fallback, invoked only when local KB context is insufficient (ADR-06). An empty result
 * (zero hits or any error/timeout) is a normal outcome, not an exceptional one: the caller treats it as
 * "web search also failed" and returns the deterministic NONE response.
 */
public interface WebSearchService {

    List<WebSearchResult> search(String query, int maxResults);
}
