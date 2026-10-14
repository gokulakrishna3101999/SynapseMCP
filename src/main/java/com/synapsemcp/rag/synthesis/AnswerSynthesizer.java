package com.synapsemcp.rag.synthesis;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.rag.dto.ChatTurn;
import com.synapsemcp.rag.retrieval.ChunkMatch;
import com.synapsemcp.rag.websearch.WebSearchResult;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * Synthesizes a prose answer with the fixed {@code gemini-3.5-flash-lite} chat model, grounded strictly in
 * either local knowledge-base chunks or web search results (design.md section 5.3). The structured
 * parts of the response (citations, web sources, confidence) are built by the caller from the retrieval
 * results directly, not asked of the model - only the natural-language answer text comes from Gemini.
 * <p>Unlike Tier 1 document extraction (deliberately fail-fast, no retry - see memory.md section 4), a
 * live user-facing chat request retries transient Gemini errors (429/5xx, and a hung call - see below)
 * up to 3 times with backoff, mirroring the embedding model's retry policy. This was added after two
 * real 503 "high demand" errors occurred back to back while testing this class.
 * <p>A real hang was also caught live: the underlying OkHttp call can block indefinitely waiting for
 * response headers with no exception at all (observed directly - a test run sat blocked for minutes
 * with flat CPU time until killed), which would make the retry policy above pointless since nothing
 * ever throws to trigger a retry. Each attempt is therefore bounded by {@code synapse.chat.timeout} on
 * its own virtual thread, the same {@code future.get(timeout)} pattern {@code MultimodalLlmExtractor}
 * already uses for Tier 1 - this class was missing that bound entirely before the hang was observed.
 */
@Component
@EnableConfigurationProperties(ChatSynthesisProperties.class)
public class AnswerSynthesizer {

    private static final String LOCAL_PROMPT = """
            Answer the user's question using ONLY the knowledge-base excerpts below. Do not use any
            outside knowledge. Check every excerpt individually before concluding the answer is not
            present - the excerpts are often short, independent fragments (e.g. a label on one line and
            its value on the next), so the fact you need may be in a short excerpt that looks unrelated to
            the others. Each excerpt is labeled with the document it came from: two or more excerpts from
            the SAME named document are parts of that one document, and connecting them (e.g. a title
            excerpt plus a separate detail excerpt from that same document) is reading the document
            correctly, not guessing - only treat it as guessing when you would have to combine facts from
            DIFFERENT documents or invent something no excerpt states. Only say the information isn't
            available after you have checked each excerpt this way. If the excerpts only partially answer
            the question, say so plainly rather than guessing. Write a natural, concise answer - do not
            mention "excerpt" numbers or chunk IDs.
            %s
            Question: %s

            Knowledge base excerpts:
            %s
            """;

    private static final String WEB_PROMPT = """
            Answer the user's question using ONLY the web search results below. Do not use any outside
            knowledge. Be concise, and make clear the answer comes from a web search rather than the
            user's own documents.
            %s
            Question: %s

            Web search results:
            %s
            """;

    private static final String HISTORY_SECTION = """

            Recent conversation so far (for context only - e.g. to resolve "it"/"what about X" - never a
            source of facts; only the excerpts/results above may ground the answer):
            %s
            """;

    private final ChatClient chatClient;
    private final RetryTemplate retryTemplate;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AnswerSynthesizer(ChatClient chatClient, ChatSynthesisProperties properties) {
        this.chatClient = chatClient;
        this.timeout = properties.timeout();
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(Duration.ofMillis(500), 2.0, Duration.ofSeconds(8))
                .retryOn(AnswerSynthesizer::isTransientOrTimeout)
                .build();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static boolean isTransientOrTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
            if (t instanceof com.google.genai.errors.ApiException api && (api.code() == 429 || api.code() >= 500)) {
                return true;
            }
        }
        return false;
    }

    public String synthesizeFromLocal(String query, List<ChunkMatch> matches, List<ChatTurn> history) {
        // Grouped by source document (in order of each document's best-ranked match), not left in flat
        // similarity-rank order: a real live test found that when a document's own chunks were scattered
        // across the ranked list (e.g. a title chunk at rank 1 and the detail chunk it needs at rank 8,
        // interleaved with six other documents' chunks in between), the model would not connect them even
        // though both were correctly labeled with the same document name - restating that instruction more
        // strongly did not fix it. Grouping each document's chunks into one contiguous excerpt removes the
        // need for the model to notice the connection across a scattered list at all.
        String context = matches.stream()
                .collect(Collectors.groupingBy(ChunkMatch::documentName, java.util.LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> "- (from \"" + e.getKey() + "\"):\n  " + e.getValue().stream()
                        .map(ChunkMatch::content)
                        .collect(Collectors.joining("\n  ")))
                .collect(Collectors.joining("\n\n"));
        return call(LOCAL_PROMPT.formatted(historySection(history), query, context));
    }

    public String synthesizeFromWeb(String query, List<WebSearchResult> results, List<ChatTurn> history) {
        String context = results.stream()
                .map(r -> "- " + r.title() + " (" + r.url() + "): " + r.content())
                .collect(Collectors.joining("\n\n"));
        return call(WEB_PROMPT.formatted(historySection(history), query, context));
    }

    private static String historySection(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        String transcript = history.stream()
                .map(t -> ("user".equals(t.role()) ? "User: " : "Assistant: ") + t.text())
                .collect(Collectors.joining("\n"));
        return HISTORY_SECTION.formatted(transcript);
    }

    private String call(String prompt) {
        String text;
        try {
            text = retryTemplate.execute(context -> callWithTimeout(prompt));
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_SYNTHESIS_FAILED",
                    "Could not generate an answer: " + describe(ex));
        }
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_SYNTHESIS_FAILED",
                    "The assistant returned no answer.");
        }
        return text.strip();
    }

    private String callWithTimeout(String prompt) {
        Future<String> future = executor.submit(() -> chatClient.prompt()
                .user(prompt)
                // 0.0, not a small positive value: this is strictly-grounded fact lookup over excerpts
                // (never creative writing), and a real live test found the model occasionally skimming
                // past a short, correct excerpt among several at temperature 0.2 - maximum determinism is
                // the right choice for "find this fact in these excerpts," not a tradeoff worth avoiding.
                .options(GoogleGenAiChatOptions.builder().temperature(0.0).build())
                .call()
                .content());
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new RuntimeException("Gemini chat call timed out after " + timeout, ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Gemini chat call failed", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("Gemini chat call was interrupted", ex);
        }
    }

    private static String describe(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
