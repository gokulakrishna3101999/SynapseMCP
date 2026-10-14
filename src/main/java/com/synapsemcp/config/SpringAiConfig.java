package com.synapsemcp.config;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.synapsemcp.ingestion.extraction.ExtractionProperties;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * The chat and embedding models are fixed in application.yml (gemini-3.5-flash-lite, gemini-embedding-2 at 768 dims);
 * nothing in the request path may override them.
 */
@Configuration
@EnableConfigurationProperties(ExtractionProperties.class)
public class SpringAiConfig {

    /**
     * The chat client for Gemini. The Google SDK retries 429/5xx on its own (5 attempts with backoff, which turned a
     * quota error into a 20-45 second wait), underneath Spring AI's retry layer. Tier 1 extraction must fail the job
     * on the first error, so the SDK is limited to a single attempt. This replaces Spring AI's auto-configured client.
     */
    @Bean
    Client googleGenAiClient(GoogleGenAiConnectionProperties connection) {
        return Client.builder()
                .apiKey(connection.getApiKey())
                .httpOptions(HttpOptions.builder()
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Unlike Tier 1 chat extraction (fail fast, no retry), embedding calls get their own Spring-AI-level retry:
     * 3 attempts with exponential backoff, triggered only by transient Gemini errors (HTTP 429 or 5xx). This
     * replaces Spring AI's auto-configured embedding model, which otherwise shares the global
     * {@code spring.ai.retry.max-attempts: 1} template that Tier 1 chat deliberately relies on.
     */
    @Bean
    GoogleGenAiTextEmbeddingModel googleGenAiTextEmbeddingModel(GoogleGenAiEmbeddingConnectionDetails connectionDetails,
                                                                 GoogleGenAiTextEmbeddingProperties properties) {
        RetryTemplate embeddingRetryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(Duration.ofMillis(500), 2.0, Duration.ofSeconds(8))
                .retryOn(SpringAiConfig::isTransientGeminiError)
                .build();
        return new GoogleGenAiTextEmbeddingModel(connectionDetails, properties.getOptions(), embeddingRetryTemplate);
    }

    private static boolean isTransientGeminiError(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ApiException api && (api.code() == 429 || api.code() >= 500)) {
                return true;
            }
        }
        return false;
    }
}
