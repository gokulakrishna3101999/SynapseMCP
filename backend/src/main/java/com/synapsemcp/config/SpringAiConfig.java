package com.synapsemcp.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.synapsemcp.ingestion.extraction.ExtractionProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The chat and embedding models are fixed in application.yml (gemini-3.5-flash, gemini-embedding-2 at 768 dims);
 * nothing in the request path may override them. The EmbeddingModel bean comes from Spring AI auto-configuration.
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
}
