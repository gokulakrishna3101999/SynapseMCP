package com.synapsemcp.ingestion.extraction;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code timeout} bounds one Tier 2 Java-parser call. Tier 2 files can be up to 10MiB (vs. Tier 1's 2MiB),
 * and a pathologically structured file (e.g. a spreadsheet with an enormous number of cells, or deeply
 * nested HTML) can take far longer to parse than its byte size suggests - unlike Tier 1, nothing bounded
 * this before Milestone 3.4's "not bounded yet" gap. Exceeding it fails the ingestion job.
 */
@Validated
@ConfigurationProperties(prefix = "synapse.ingestion.tier2")
public record Tier2ExtractionProperties(@NotNull Duration timeout) {
}
