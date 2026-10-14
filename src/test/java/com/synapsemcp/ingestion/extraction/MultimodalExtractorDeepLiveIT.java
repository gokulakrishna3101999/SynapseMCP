package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.config.SpringAiConfig;
import com.synapsemcp.document.model.FileCategory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
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
 * Harder real-API cases: multi-page PDFs, tables, scanned (image-only) PDFs, JPEG and WebP.
 * Skipped unless GEMINI_API_KEY is set. Run with: ./mvnw test -Dtest=MultimodalExtractorDeepLiveIT
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = {SpringAiConfig.class, MultimodalLlmExtractor.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "synapse.ingestion.tier1.timeout=PT10M")
@ImportAutoConfiguration({
        SpringAiRetryAutoConfiguration.class,
        ToolCallingAutoConfiguration.class,
        ChatClientAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class})
class MultimodalExtractorDeepLiveIT {

    @Autowired
    MultimodalLlmExtractor extractor;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private ExtractionResult run(String name, FileCategory category, String contentType, byte[] bytes) {
        long start = System.nanoTime();
        ExtractionResult result = extractor.extract(new ExtractionRequest(name, category, contentType, bytes));
        System.out.printf("%n[%s] %d bytes in, %d chars out, %.1fs%n>>>%n%s%n<<<%n", name, bytes.length,
                result.markdown().length(), (System.nanoTime() - start) / 1e9, abbreviate(result.markdown()));
        return result;
    }

    private static String abbreviate(String s) {
        return s.length() <= 900 ? s : s.substring(0, 900) + "\n...[" + (s.length() - 900) + " more chars]";
    }

    @Test
    void multiPagePdfKeepsEveryPageInOrder() {
        List<TestDocuments.Page> pages = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            pages.add(TestDocuments.Page.text(TestDocuments.textLines(14, 720, List.of(
                    "Chapter " + p + " Heading",
                    "This is the body of page " + p + " of the report.",
                    "Unique marker for page " + p + ": MARK-" + p + "-" + (p * 1111)))));
        }
        String md = run("multipage.pdf", FileCategory.PDF, "application/pdf", TestDocuments.pdf(pages)).markdown();
        for (int p = 1; p <= 5; p++) {
            assertThat(md).contains("MARK-" + p + "-" + (p * 1111));
        }
        for (int p = 1; p < 5; p++) {
            assertThat(md.indexOf("MARK-" + p + "-")).isLessThan(md.indexOf("MARK-" + (p + 1) + "-"));
        }
    }

    @Test
    void ruledTableBecomesAMarkdownTable() {
        String[][] rows = {
                {"Item", "Qty", "Price"},
                {"Widget", "12", "3.50"},
                {"Gadget", "7", "19.99"},
                {"Gizmo", "100", "0.75"}};
        String md = run("table.pdf", FileCategory.PDF, "application/pdf",
                TestDocuments.pdf(List.of(TestDocuments.Page.text(TestDocuments.tablePage("Price List", rows))))).markdown();
        assertThat(md).containsPattern("\\|\\s*:?-{3,}").doesNotContain("```");
        // every row (header included) must be one pipe-delimited line holding all three of its cells
        for (String[] row : rows) {
            assertThat(md.lines().anyMatch(l -> l.contains("|")
                    && l.contains(row[0]) && l.contains(row[1]) && l.contains(row[2])))
                    .as("row " + String.join("/", row)).isTrue();
        }
    }

    @Test
    void scannedPdfWithNoTextLayerIsReadByOcr() throws Exception {
        byte[] jpeg = TestDocuments.encode(TestDocuments.textImage(1224, 1584, 54, List.of(
                "SCANNED CONTRACT", "Agreement number SCAN-55821", "Signed on 14 March 2026")), "jpg");
        String md = run("scanned.pdf", FileCategory.PDF, "application/pdf",
                TestDocuments.pdf(List.of(TestDocuments.Page.scan(jpeg, 1224, 1584)))).markdown();
        assertThat(md).contains("SCAN-55821").containsIgnoringCase("scanned contract");
    }

    @Test
    void jpegImageIsRead() throws Exception {
        byte[] jpeg = TestDocuments.encode(TestDocuments.textImage(900, 220, 40, List.of(
                "Receipt JPG-30417", "Paid 89 EUR")), "jpg");
        String md = run("receipt.jpg", FileCategory.IMAGE, "image/jpeg", jpeg).markdown();
        assertThat(md).contains("JPG-30417").contains("89");
    }

    @Test
    void webpImageIsRead() throws Exception {
        byte[] webp = TestDocuments.encode(TestDocuments.textImage(900, 220, 40, List.of(
                "Ticket WEBP-77201", "Seat 14C")), "webp");
        assertThat(webp).as("webp writer produced bytes").isNotEmpty();
        String md = run("ticket.webp", FileCategory.IMAGE, "image/webp", webp).markdown();
        assertThat(md).contains("WEBP-77201").contains("14C");
    }
}
