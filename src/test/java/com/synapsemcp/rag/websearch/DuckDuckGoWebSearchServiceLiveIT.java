package com.synapsemcp.rag.websearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Hits the real DuckDuckGo Instant Answer API (no key needed). Needs outbound internet access; not in
 * the default `*Test` surefire pattern, like the project's other Live/IT classes.
 * Run with: ./mvnw test -Dtest=DuckDuckGoWebSearchServiceLiveIT
 */
class DuckDuckGoWebSearchServiceLiveIT {

    private final DuckDuckGoWebSearchService service = new DuckDuckGoWebSearchService(
            RestClient.builder(), new WebSearchProperties(Duration.ofSeconds(5)), new ObjectMapper());

    @Test
    void wellKnownTopicReturnsAtLeastOneRealResult() {
        List<WebSearchResult> results = service.search("Java programming language", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).url()).startsWith("http");
        assertThat(results.get(0).content()).isNotBlank();
    }

    @Test
    void obscureNonsenseQueryReturnsEmptyNotAnError() {
        // Demonstrates the accepted limitation documented on the class: most queries that aren't a
        // well-known topic return nothing, and that must be a plain empty list, not an exception.
        List<WebSearchResult> results = service.search("asdkjqwoeiuzxcv9999nonsense query string", 5);

        assertThat(results).isEmpty();
    }
}
