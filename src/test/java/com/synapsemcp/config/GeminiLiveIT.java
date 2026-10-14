package com.synapsemcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;

/**
 * Calls the real Gemini API with the app's own configuration. Skipped unless GEMINI_API_KEY is set.
 * Run with: ./mvnw test -Dtest=GeminiLiveIT
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = {SpringAiConfig.class, GeminiLiveIT.Props.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ImportAutoConfiguration({
        org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration.class,
        org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration.class,
        ChatClientAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
        GoogleGenAiTextEmbeddingAutoConfiguration.class})
class GeminiLiveIT {

    @org.springframework.context.annotation.Configuration
    static class Props {
    }

    @Autowired
    ChatClient chatClient;

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void chatModelAnswers() {
        String answer = chatClient.prompt().user("Reply with exactly: OK").call().content();
        assertThat(answer).contains("OK");
    }

    @Test
    void embeddingModelReturns768Dimensions() {
        float[] vector = embeddingModel.embed("hello world");
        assertThat(vector).hasSize(768);
    }
}
