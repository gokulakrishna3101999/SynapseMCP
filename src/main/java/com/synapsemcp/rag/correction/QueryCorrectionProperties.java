package com.synapsemcp.rag.correction;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code timeout} bounds one Gemini chat call inside {@code QuerySpellCorrector}, the same
 * {@code future.get(timeout)} pattern used by Tier 1 extraction and answer synthesis - the underlying
 * OkHttp call can hang indefinitely with no exception otherwise (observed live in AnswerSynthesizer).
 * Kept short: correction is a best-effort accuracy improvement over the raw query, not essential to
 * answering it, so a slow Gemini call should fall back to the original query quickly rather than add
 * much latency to every chat request.
 */
@Validated
@ConfigurationProperties(prefix = "synapse.query.correction")
public record QueryCorrectionProperties(@NotNull Duration timeout) {
}
