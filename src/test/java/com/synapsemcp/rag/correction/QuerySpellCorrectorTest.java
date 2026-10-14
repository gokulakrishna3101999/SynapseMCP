package com.synapsemcp.rag.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

/**
 * Covers the two properties that matter for this class: a real correction is picked up and used, and any
 * failure (API error, timeout, blank output) falls back to the original query rather than propagating -
 * this is a best-effort accuracy improvement, never allowed to fail a chat request on its own.
 */
class QuerySpellCorrectorTest {

    private static QuerySpellCorrector newCorrector(ChatClient chatClient, Duration timeout) {
        return new QuerySpellCorrector(chatClient, new QueryCorrectionProperties(timeout));
    }

    private static ChatClientRequestSpec stubRequestSpec(ChatClient chatClient) {
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class, org.mockito.Answers.RETURNS_SELF);
        when(chatClient.prompt()).thenReturn(requestSpec);
        return requestSpec;
    }

    @Test
    void aCorrectedResponseFromGeminiIsReturned() {
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ChatClientRequestSpec requestSpec = stubRequestSpec(chatClient);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("what is the phoenix budget");

        QuerySpellCorrector corrector = newCorrector(chatClient, Duration.ofSeconds(5));

        assertThat(corrector.correct("wat is teh phoenix budgte")).isEqualTo("what is the phoenix budget");
    }

    @Test
    void wrappingQuotesAroundTheModelOutputAreStripped() {
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ChatClientRequestSpec requestSpec = stubRequestSpec(chatClient);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("\"what is the phoenix budget\"");

        QuerySpellCorrector corrector = newCorrector(chatClient, Duration.ofSeconds(5));

        assertThat(corrector.correct("wat is teh phoenix budgte")).isEqualTo("what is the phoenix budget");
    }

    @Test
    void aGeminiApiErrorFallsBackToTheOriginalQueryInsteadOfThrowing() {
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ChatClientRequestSpec requestSpec = stubRequestSpec(chatClient);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenThrow(new RuntimeException("Gemini 503"));

        QuerySpellCorrector corrector = newCorrector(chatClient, Duration.ofSeconds(5));

        assertThat(corrector.correct("some query")).isEqualTo("some query");
    }

    @Test
    void aBlankModelOutputFallsBackToTheOriginalQuery() {
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ChatClientRequestSpec requestSpec = stubRequestSpec(chatClient);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("   ");

        QuerySpellCorrector corrector = newCorrector(chatClient, Duration.ofSeconds(5));

        assertThat(corrector.correct("some query")).isEqualTo("some query");
    }

    /**
     * Mirrors AnswerSynthesizerTimeoutTest's pattern: a real hung OkHttp call was observed live elsewhere
     * in this codebase with no exception at all, so correction must be bounded by its own timeout too.
     */
    @Test
    void aHungCallFallsBackToTheOriginalQueryAfterTheConfiguredTimeout() {
        ChatClient chatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ChatClientRequestSpec requestSpec = stubRequestSpec(chatClient);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenAnswer(inv -> {
            Thread.sleep(5_000);
            throw new AssertionError("should have been abandoned before completing");
        });

        QuerySpellCorrector corrector = newCorrector(chatClient, Duration.ofMillis(200));

        long start = System.nanoTime();
        assertThat(corrector.correct("some query")).isEqualTo("some query");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("must fall back well before the 5s hang completes").isLessThan(4_000);
    }

    @Test
    void blankInputIsReturnedAsIsWithoutCallingGemini() {
        ChatClient chatClient = mock(ChatClient.class);

        QuerySpellCorrector corrector = newCorrector(chatClient, Duration.ofSeconds(5));

        assertThat(corrector.correct("   ")).isEqualTo("   ");
        assertThat(corrector.correct(null)).isNull();
        org.mockito.Mockito.verifyNoInteractions(chatClient);
    }
}
