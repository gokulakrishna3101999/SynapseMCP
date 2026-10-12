package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.model.FileCategory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

class MultimodalLlmExtractorTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

    private MultimodalLlmExtractor extractor(Duration timeout) {
        return new MultimodalLlmExtractor(chatClient, new ExtractionProperties(timeout));
    }

    @SuppressWarnings("unchecked")
    private org.mockito.stubbing.OngoingStubbing<ChatResponse> gemini() {
        return when(chatClient.prompt().user(any(Consumer.class)).options(any(ChatOptions.class)).call().chatResponse());
    }

    private static ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    private static ExtractionRequest pdf(int size) {
        return new ExtractionRequest("a.pdf", FileCategory.PDF, "application/pdf", new byte[size]);
    }

    @Test
    void returnsMarkdownOnNormalStop() {
        gemini().thenReturn(response("# Title\n\nBody", "STOP"));
        assertThat(extractor(Duration.ofSeconds(5)).extract(pdf(10)).markdown()).isEqualTo("# Title\n\nBody");
    }

    @Test
    void unwrapsAWholeOutputCodeFence() {
        gemini().thenReturn(response("```markdown\n# Title\n```", "STOP"));
        assertThat(extractor(Duration.ofSeconds(5)).extract(pdf(10)).markdown()).isEqualTo("# Title");
    }

    @Test
    void keepsInnerCodeFencesUntouched() {
        String text = "Intro\n\n```java\nint x;\n```\n\nOutro";
        gemini().thenReturn(response(text, "STOP"));
        assertThat(extractor(Duration.ofSeconds(5)).extract(pdf(10)).markdown()).isEqualTo(text);
    }

    @Test
    void nulAndControlCharactersAreRemovedBecausePostgresCannotStoreThem() {
        gemini().thenReturn(response("Hello\u0000 wor\u0001ld\r\nline2", "STOP"));
        assertThat(extractor(Duration.ofSeconds(5)).extract(pdf(10)).markdown()).isEqualTo("Hello world\nline2");
    }

    @Test
    void blankOutputFailsTheJob() {
        gemini().thenReturn(response("   \n ", "STOP"));
        assertThatThrownBy(() -> extractor(Duration.ofSeconds(5)).extract(pdf(10)))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("No readable content");
    }

    @Test
    void noContentMarkerFailsTheJob() {
        gemini().thenReturn(response("NO_CONTENT", "STOP"));
        assertThatThrownBy(() -> extractor(Duration.ofSeconds(5)).extract(pdf(10)))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("No readable content");
    }

    @Test
    void truncatedOrBlockedOutputFailsTheJob() {
        for (String reason : List.of("MAX_TOKENS", "SAFETY", "RECITATION")) {
            gemini().thenReturn(response("partial text", reason));
            assertThatThrownBy(() -> extractor(Duration.ofSeconds(5)).extract(pdf(10)))
                    .as(reason).isInstanceOf(DocumentExtractionException.class).hasMessageContaining(reason);
        }
    }

    @Test
    void apiErrorFailsTheJobWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        gemini().thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("429 quota");
        });
        assertThatThrownBy(() -> extractor(Duration.ofSeconds(5)).extract(pdf(10)))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("429 quota");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void timeoutFailsTheJob() {
        gemini().thenAnswer(inv -> {
            Thread.sleep(2000);
            return response("late", "STOP");
        });
        assertThatThrownBy(() -> extractor(Duration.ofMillis(200)).extract(pdf(10)))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("timed out");
    }

    @Test
    void rejectsUnsupportedCategoryEmptyAndOversizedFilesBeforeCallingGemini() {
        MultimodalLlmExtractor extractor = extractor(Duration.ofSeconds(5));
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("a.docx", FileCategory.WORD,
                "application/msword", new byte[10]))).isInstanceOf(DocumentExtractionException.class);
        assertThatThrownBy(() -> extractor.extract(pdf(0))).isInstanceOf(DocumentExtractionException.class);
        assertThatThrownBy(() -> extractor.extract(pdf((int) IngestionTierRouter.TIER_1_MAX_BYTES + 1)))
                .isInstanceOf(DocumentExtractionException.class);
        // No stubbing was set up, so a call would have returned null and failed with a different message.
        verify(chatClient, times(0)).prompt();
    }
}
