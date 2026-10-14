package com.synapsemcp.rag.websearch;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code timeout} bounds one DuckDuckGo call (plan.md's risk table: 2.0s for the original Tavily client). */
@Validated
@ConfigurationProperties(prefix = "synapse.websearch")
public record WebSearchProperties(@NotNull Duration timeout) {
}
