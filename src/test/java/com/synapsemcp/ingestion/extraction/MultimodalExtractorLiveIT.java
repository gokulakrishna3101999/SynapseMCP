package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.config.SpringAiConfig;
import com.synapsemcp.document.model.FileCategory;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
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
 * Sends a real generated PDF and PNG to the real Gemini API through the app's own beans.
 * Skipped unless GEMINI_API_KEY is set. Run with: ./mvnw test -Dtest=MultimodalExtractorLiveIT
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = {SpringAiConfig.class, MultimodalLlmExtractor.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ImportAutoConfiguration({
        SpringAiRetryAutoConfiguration.class,
        ToolCallingAutoConfiguration.class,
        ChatClientAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class})
class MultimodalExtractorLiveIT {

    @Autowired
    MultimodalLlmExtractor extractor;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void extractsTextFromARealPdf() {
        byte[] pdf = buildPdf(List.of("Quarterly Report", "Revenue grew 18 percent to 4.2 million", "Project code ZEBRA-7731"));
        ExtractionResult result = extractor.extract(new ExtractionRequest("report.pdf", FileCategory.PDF,
                "application/pdf", pdf));
        System.out.println("PDF MARKDOWN >>>\n" + result.markdown() + "\n<<<");
        assertThat(result.markdown()).contains("ZEBRA-7731").contains("4.2");
    }

    @Test
    void readsTextFromARealPng() throws Exception {
        BufferedImage image = new BufferedImage(900, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 220);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        g.drawString("Invoice INV-90417", 40, 90);
        g.drawString("Total due 1250 USD", 40, 160);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        ExtractionResult result = extractor.extract(new ExtractionRequest("invoice.png", FileCategory.IMAGE,
                "image/png", out.toByteArray()));
        System.out.println("PNG MARKDOWN >>>\n" + result.markdown() + "\n<<<");
        assertThat(result.markdown()).contains("INV-90417").contains("1250");
    }

    @Test
    void corruptPdfFailsTheJobQuicklyWithoutRetry() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("bad.pdf", FileCategory.PDF,
                "application/pdf", "%PDF-1.4 this is not a real pdf".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(ex -> System.out.println("CORRUPT PDF FAILURE >>> " + ex.getMessage()));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        System.out.println("corrupt pdf failed after " + seconds + "s");
        assertThat(seconds).isLessThan(60);
    }

    /** Minimal valid single-page PDF (Helvetica text) with correct xref offsets. */
    private static byte[] buildPdf(List<String> lines) {
        StringBuilder content = new StringBuilder("BT /F1 18 Tf 72 720 Td 24 TL\n");
        for (String line : lines) {
            content.append('(').append(line).append(") Tj T*\n");
        }
        content.append("ET");
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
