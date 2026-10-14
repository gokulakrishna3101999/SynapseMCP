package com.synapsemcp.rag.synthesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.rag.retrieval.ChunkMatch;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

/**
 * A real OkHttp call to Gemini was observed (live, not hypothetical) to block indefinitely waiting for
 * response headers with no exception at all - which would make AnswerSynthesizer's retry-on-failure
 * policy pointless, since nothing ever throws to trigger a retry. Proves the timeout actually bounds a
 * hung call instead of waiting forever, using a ChatClient mock whose call() blocks past the timeout -
 * mirroring IngestionOrchestratorTier2TimeoutTest's pattern for the same class of bug.
 */
class AnswerSynthesizerTimeoutTest {

    @Test
    void aHungChatCallFailsAfterTheConfiguredTimeoutInsteadOfHangingForever() throws Exception {
        ChatClient chatClient = org.mockito.Mockito.mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        ChatClientRequestSpec requestSpec = org.mockito.Mockito.mock(ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        CallResponseSpec callResponseSpec = org.mockito.Mockito.mock(CallResponseSpec.class);
        org.mockito.Mockito.when(chatClient.prompt()).thenReturn(requestSpec);
        org.mockito.Mockito.when(requestSpec.call()).thenReturn(callResponseSpec);
        // Simulates the real hung-OkHttp-call failure mode: the call never returns on its own.
        org.mockito.Mockito.when(callResponseSpec.content()).thenAnswer(inv -> {
            Thread.sleep(5_000);
            throw new AssertionError("should have been abandoned before completing");
        });

        AnswerSynthesizer synthesizer = new AnswerSynthesizer(chatClient, new ChatSynthesisProperties(Duration.ofMillis(200)));
        List<ChunkMatch> matches = List.of(new ChunkMatch(UUID.randomUUID(), UUID.randomUUID(), "doc.txt", "content", 0.9));

        long start = System.nanoTime();
        assertThatThrownBy(() -> synthesizer.synthesizeFromLocal("question", matches, List.of()))
                .isInstanceOf(ApiException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 3 retry attempts at ~200ms each plus backoff, still nowhere near the 5s the mock blocks for.
        assertThat(elapsedMs).as("must fail well before the 5s hang completes").isLessThan(4_000);
    }
}
