package com.synapsemcp.rag.websearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsemcp.rag.websearch.DuckDuckGoResponse.RelatedTopic;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Web search fallback using DuckDuckGo's free Instant Answer API (no key required) in place of the
 * Tavily Search API that plan.md/design.md originally specify - a deliberate substitution, per the
 * user's decision. Unlike Tavily, this is NOT a general ranked web-search API: it mostly returns a
 * single Wikipedia-style abstract plus a handful of loosely related topics, so many ordinary queries
 * will return zero or one usable result rather than design.md's "top 3-5 authoritative web snippets".
 * That is an accepted limitation of the chosen replacement, not a bug - callers already treat an empty
 * result as a normal "web search also failed" outcome (ADR-06's deterministic NONE response).
 * <p>The API quirkily serves its JSON body as {@code Content-Type: application/x-javascript} regardless
 * of the {@code format=json} query param, which Spring's default message converters reject outright -
 * confirmed by a real call in {@code DuckDuckGoWebSearchServiceLiveIT} before this fix. The body is
 * therefore fetched as a raw string and parsed with Jackson directly, bypassing content-type negotiation.
 */
@Component
@EnableConfigurationProperties(WebSearchProperties.class)
public class DuckDuckGoWebSearchService implements WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoWebSearchService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DuckDuckGoWebSearchService(RestClient.Builder builder, WebSearchProperties properties,
                                       ObjectMapper objectMapper) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.timeout())
                .withReadTimeout(properties.timeout());
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        this.restClient = builder.baseUrl("https://api.duckduckgo.com")
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("no_html", "1")
                            .queryParam("skip_disambig", "1")
                            .build())
                    .retrieve()
                    .body(String.class);
            DuckDuckGoResponse response = objectMapper.readValue(body, DuckDuckGoResponse.class);
            return flatten(response, maxResults);
        } catch (RestClientException | JsonProcessingException ex) {
            log.warn("DuckDuckGo search failed for query (treated as zero results): {}", ex.getMessage());
            return List.of();
        }
    }

    /** Package-visible for direct unit testing without a network call. */
    static List<WebSearchResult> flatten(DuckDuckGoResponse response, int maxResults) {
        List<WebSearchResult> results = new ArrayList<>();
        if (response == null) {
            return results;
        }
        if (isUsable(response.abstractText(), response.abstractUrl())) {
            String title = isBlank(response.heading()) ? response.abstractSource() : response.heading();
            results.add(new WebSearchResult(title, response.abstractUrl(), response.abstractText()));
        }
        collectRelatedTopics(response.relatedTopics(), results, maxResults);
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    private static void collectRelatedTopics(List<RelatedTopic> topics, List<WebSearchResult> out, int maxResults) {
        if (topics == null) {
            return;
        }
        for (RelatedTopic topic : topics) {
            if (out.size() >= maxResults) {
                return;
            }
            if (topic.topics() != null && !topic.topics().isEmpty()) {
                collectRelatedTopics(topic.topics(), out, maxResults);
                continue;
            }
            if (isUsable(topic.text(), topic.firstUrl())) {
                out.add(new WebSearchResult(titleFrom(topic.text()), topic.firstUrl(), topic.text()));
            }
        }
    }

    /** DuckDuckGo's related-topic text is conventionally "Title - description"; falls back to the full text. */
    private static String titleFrom(String text) {
        int separator = text.indexOf(" - ");
        return separator > 0 ? text.substring(0, separator) : text;
    }

    private static boolean isUsable(String text, String url) {
        return !isBlank(text) && !isBlank(url);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
