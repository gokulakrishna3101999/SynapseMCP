package com.synapsemcp.rag.websearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Subset of the DuckDuckGo Instant Answer API's JSON shape (https://api.duckduckgo.com/?format=json)
 * actually used here. Unmodeled fields (Image, Definition, Redirect, Entity, ...) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DuckDuckGoResponse(
        @JsonProperty("Heading") String heading,
        @JsonProperty("AbstractText") String abstractText,
        @JsonProperty("AbstractURL") String abstractUrl,
        @JsonProperty("AbstractSource") String abstractSource,
        @JsonProperty("RelatedTopics") List<RelatedTopic> relatedTopics) {

    /**
     * Either a single result ({@code text}/{@code firstUrl} set) or a named category grouping more
     * results under {@code topics} - the Instant Answer API mixes both shapes in the same array.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RelatedTopic(
            @JsonProperty("Text") String text,
            @JsonProperty("FirstURL") String firstUrl,
            @JsonProperty("Topics") List<RelatedTopic> topics) {
    }
}
