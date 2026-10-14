package com.synapsemcp.ingestion.extraction;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code timeout} bounds one Tier 1 Gemini call; exceeding it fails the ingestion job. */
@Validated
@ConfigurationProperties(prefix = "synapse.ingestion.tier1")
public record ExtractionProperties(@NotNull Duration timeout) {
}
