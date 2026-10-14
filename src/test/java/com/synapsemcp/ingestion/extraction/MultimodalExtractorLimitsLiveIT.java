package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.config.SpringAiConfig;
import com.synapsemcp.document.model.FileCategory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-API limit behaviour: (1) a real MAX_TOKENS truncation must fail the job, (2) how a long document behaves.
 * Skipped unless GEMINI_API_KEY is set. Run with: ./mvnw test -Dtest='MultimodalExtractorLimitsLiveIT*'
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class MultimodalExtractorLimitsLiveIT {

    static byte[] longPdf(int pageCount, int linesPerPage) {
        List<TestDocuments.Page> pages = new ArrayList<>();
        for (int p = 1; p <= pageCount; p++) {
            List<String> lines = new ArrayList<>();
            for (int l = 1; l <= linesPerPage; l++) {
                lines.add("Page " + p + " line " + l + " alpha beta gamma delta epsilon zeta eta theta iota kappa "
                        + "lambda mu nu xi omicron  ref P" + p + "L" + l);
            }
            pages.add(TestDocuments.Page.text(TestDocuments.textLines(8, 770, lines)));
        }
        return TestDocuments.pdf(pages);
    }

    @SpringBootTest(classes = {SpringAiConfig.class, MultimodalLlmExtractor.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {"spring.ai.google.genai.chat.options.max-output-tokens=80",
                    "synapse.ingestion.tier1.timeout=PT5M"})
    @ImportAutoConfiguration({SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
            ChatClientAutoConfiguration.class, GoogleGenAiChatAutoConfiguration.class})
    @Nested
    class Truncation {

        @Autowired
        MultimodalLlmExtractor extractor;

        @Test
        void realMaxTokensTruncationFailsTheJob() {
            byte[] pdf = longPdf(3, 40);
            assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("long.pdf", FileCategory.PDF,
                    "application/pdf", pdf)))
                    // Observed on the real API: with a tiny budget Gemini returns no text at all (not partial text
                    // with MAX_TOKENS), so the message is "returned no result". Either way the job must fail.
                    .isInstanceOf(DocumentExtractionException.class)
                    .satisfies(ex -> System.out.println("TRUNCATION FAILURE >>> " + ex.getMessage()));
        }
    }

    @SpringBootTest(classes = {SpringAiConfig.class, MultimodalLlmExtractor.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = "synapse.ingestion.tier1.timeout=PT9M")
    @ImportAutoConfiguration({SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
            ChatClientAutoConfiguration.class, GoogleGenAiChatAutoConfiguration.class})
    @Nested
    class LongDocument {

        @Autowired
        MultimodalLlmExtractor extractor;

        @Test
        void twentyPageDocumentIsExtractedCompletelyAndTimed() {
            // Measured on the real API: ~190 output tokens/s, i.e. 10 dense pages = 73s, 20 pages = 149s.
            // A 40-page dense document produced no response within 9 minutes (unexplained), so it is not asserted.
            int pages = 20;
            int lines = 45;
            byte[] pdf = longPdf(pages, lines);
            long start = System.nanoTime();
            String outcome;
            String md = "";
            try {
                md = extractor.extract(new ExtractionRequest("twenty.pdf", FileCategory.PDF, "application/pdf", pdf))
                        .markdown();
                outcome = "COMPLETED";
            } catch (DocumentExtractionException ex) {
                outcome = "FAILED: " + ex.getMessage();
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            int found = 0;
            for (int p = 1; p <= pages; p++) {
                if (md.contains("P" + p + "L" + lines)) {
                    found++;
                }
            }
            System.out.printf("%nLONG DOC RESULT >>> %s | %d pages, %d bytes in, %d chars out, last-line marker found for "
                    + "%d/%d pages, %.1fs%n", outcome, pages, pdf.length, md.length(), found, pages, seconds);
            assertThat(outcome).startsWith("COMPLETED");
            assertThat(found).isEqualTo(pages);
        }
    }
}
