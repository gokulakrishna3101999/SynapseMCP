package com.synapsemcp.rag.synthesis;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code timeout} bounds one Gemini chat call inside {@code AnswerSynthesizer}. Without this, a single
 * hung call (OkHttp blocking forever waiting for response headers - a real, observed Gemini failure
 * mode, not hypothetical) would never let {@code AnswerSynthesizer}'s retry-on-transient-error policy
 * even get a chance to retry, since nothing ever throws to trigger it. Not yet calibrated against real
 * measured chat latencies - a conservative starting value, same status as Tier 2's own timeout.
 */
@Validated
@ConfigurationProperties(prefix = "synapse.chat")
public record ChatSynthesisProperties(@NotNull Duration timeout) {
}
