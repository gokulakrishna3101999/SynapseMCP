package com.synapsemcp.rag.websearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.rag.websearch.DuckDuckGoResponse.RelatedTopic;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the response-flattening logic directly, with hand-built fixtures matching the real API's shape. */
class DuckDuckGoWebSearchServiceTest {

    @Test
    void nullResponseYieldsNoResults() {
        assertThat(DuckDuckGoWebSearchService.flatten(null, 5)).isEmpty();
    }

    @Test
    void emptyResponseYieldsNoResults() {
        DuckDuckGoResponse response = new DuckDuckGoResponse(null, null, null, null, null);
        assertThat(DuckDuckGoWebSearchService.flatten(response, 5)).isEmpty();
    }

    @Test
    void abstractAloneBecomesTheFirstResult() {
        DuckDuckGoResponse response = new DuckDuckGoResponse(
                "Java (programming language)", "Java is a high-level, class-based language.",
                "https://en.wikipedia.org/wiki/Java", "Wikipedia", null);

        List<WebSearchResult> results = DuckDuckGoWebSearchService.flatten(response, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Java (programming language)");
        assertThat(results.get(0).url()).isEqualTo("https://en.wikipedia.org/wiki/Java");
        assertThat(results.get(0).content()).contains("high-level");
    }

    @Test
    void abstractWithoutHeadingFallsBackToAbstractSourceAsTitle() {
        DuckDuckGoResponse response = new DuckDuckGoResponse(
                null, "Some abstract text.", "https://example.com", "ExampleSource", null);

        List<WebSearchResult> results = DuckDuckGoWebSearchService.flatten(response, 5);

        assertThat(results.get(0).title()).isEqualTo("ExampleSource");
    }

    @Test
    void relatedTopicsAreFlattenedAndTitleIsSplitOnDash() {
        RelatedTopic topic1 = new RelatedTopic("Topic One - a short description", "https://example.com/1", null);
        RelatedTopic topic2 = new RelatedTopic("Topic Two with no dash separator", "https://example.com/2", null);
        DuckDuckGoResponse response = new DuckDuckGoResponse(null, null, null, null, List.of(topic1, topic2));

        List<WebSearchResult> results = DuckDuckGoWebSearchService.flatten(response, 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("Topic One");
        assertThat(results.get(0).content()).isEqualTo("Topic One - a short description");
        assertThat(results.get(1).title()).isEqualTo("Topic Two with no dash separator");
    }

    @Test
    void nestedCategoryGroupsAreRecursivelyFlattened() {
        // The real API mixes plain results with named category groups in the same RelatedTopics array.
        RelatedTopic nested1 = new RelatedTopic("Nested One", "https://example.com/n1", null);
        RelatedTopic nested2 = new RelatedTopic("Nested Two", "https://example.com/n2", null);
        RelatedTopic group = new RelatedTopic(null, null, List.of(nested1, nested2));
        DuckDuckGoResponse response = new DuckDuckGoResponse(null, null, null, null, List.of(group));

        List<WebSearchResult> results = DuckDuckGoWebSearchService.flatten(response, 5);

        assertThat(results).extracting(WebSearchResult::title).containsExactly("Nested One", "Nested Two");
    }

    @Test
    void resultsAreCappedAtMaxResults() {
        RelatedTopic t1 = new RelatedTopic("One", "https://example.com/1", null);
        RelatedTopic t2 = new RelatedTopic("Two", "https://example.com/2", null);
        RelatedTopic t3 = new RelatedTopic("Three", "https://example.com/3", null);
        DuckDuckGoResponse response = new DuckDuckGoResponse(null, null, null, null, List.of(t1, t2, t3));

        List<WebSearchResult> results = DuckDuckGoWebSearchService.flatten(response, 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void topicsMissingAUrlOrTextAreSkipped() {
        RelatedTopic noUrl = new RelatedTopic("Has text but no URL", null, null);
        RelatedTopic noText = new RelatedTopic(null, "https://example.com/no-text", null);
        RelatedTopic valid = new RelatedTopic("Valid", "https://example.com/valid", null);
        DuckDuckGoResponse response = new DuckDuckGoResponse(null, null, null, null, List.of(noUrl, noText, valid));

        List<WebSearchResult> results = DuckDuckGoWebSearchService.flatten(response, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Valid");
    }
}
