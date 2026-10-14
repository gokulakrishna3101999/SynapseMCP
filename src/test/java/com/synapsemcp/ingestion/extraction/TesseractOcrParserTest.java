package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.parser.TesseractOcrDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.TesseractOcrEngine;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real Tesseract OCR (Tess4J native libraries + bundled English data) on generated images. */
class TesseractOcrParserTest {

    private final TesseractOcrDocumentParser parser = new TesseractOcrDocumentParser(new TesseractOcrEngine());

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private ExtractionRequest request(String name, String contentType, byte[] bytes) {
        return new ExtractionRequest(name, FileCategory.IMAGE, contentType, bytes);
    }

    private byte[] invoice(String format) throws Exception {
        BufferedImage image = TestDocuments.textImage(1400, 420, 72, List.of("Invoice INV90417", "Total due 1250 USD"));
        return TestDocuments.encode(image, format);
    }

    private static String squashed(String s) {
        return s.replaceAll("\\s+", " ");
    }

    @Test
    void pngTextIsRead() throws Exception {
        String text = squashed(parser.parse(request("a.png", "image/png", invoice("png"))).markdown());
        System.out.println("[ocr png] " + text);
        assertThat(text).contains("INV90417").contains("1250");
    }

    @Test
    void jpegTextIsRead() throws Exception {
        String text = squashed(parser.parse(request("a.jpg", "image/jpeg", invoice("jpg"))).markdown());
        System.out.println("[ocr jpg] " + text);
        assertThat(text).contains("INV90417").contains("1250");
    }

    @Test
    void webpTextIsRead() throws Exception {
        String text = squashed(parser.parse(request("a.webp", "image/webp", invoice("webp"))).markdown());
        System.out.println("[ocr webp] " + text);
        assertThat(text).contains("INV90417").contains("1250");
    }

    @Test
    void imageWithoutTextGivesAnEmptyResultForTheRouterToReject() throws Exception {
        BufferedImage blank = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        var g = blank.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 600, 300);
        g.dispose();
        byte[] png = TestDocuments.encode(blank, "png");
        assertThat(parser.parse(request("blank.png", "image/png", png)).markdown()).isBlank();
        assertThatThrownBy(() -> new JavaParserExtractor(List.of(parser)).extract(request("blank.png", "image/png", png)))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("No readable text");
    }

    /**
     * Reproduces the real failure this dual-pass merge fixes: a single global-threshold OCR pass cannot
     * read both a dark-on-light region and a light-on-dark region in the same image. Top half: black text
     * on white. Bottom half: white text on black - the polarity a plain single pass tends to miss.
     */
    @Test
    void mixedPolarityImageIsReadInBothRegionsViaTheInvertedSecondPass() throws Exception {
        int width = 1400;
        int height = 600;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, height / 2);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 72));
        g.drawString("Invoice INV90417", 40, 160);

        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, height / 2, width, height / 2);
        g.setColor(java.awt.Color.WHITE);
        g.drawString("Total due 1250 USD", 40, height / 2 + 160);
        g.dispose();

        byte[] png = TestDocuments.encode(image, "png");
        String text = squashed(parser.parse(request("mixed.png", "image/png", png)).markdown());
        System.out.println("[ocr mixed-polarity] " + text);

        assertThat(text).contains("INV90417").contains("1250");
    }

    /**
     * Reproduces, in miniature, a real reliability failure found live: a real scanned PDF page (letterhead
     * gradients, logos) produced so many brightness bands that OCR-ing each one twice took over 2 minutes
     * and hit the ingestion timeout - the same file had completed in ~18s on an earlier, less noisy run.
     * A striped image with many narrow bands must stay fast (the cap falls back to one whole-image pass)
     * instead of multiplying into dozens of Tesseract calls.
     */
    @Test
    void manyNarrowBandsFallsBackToOneWholeImagePassInsteadOfExploding() throws Exception {
        int width = 800;
        int height = 800;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        int stripeHeight = 20; // 40 stripes - comfortably over the band cap if each becomes its own band
        for (int y = 0; y < height; y += stripeHeight) {
            g.setColor((y / stripeHeight) % 2 == 0 ? java.awt.Color.WHITE : new java.awt.Color(40, 120, 160));
            g.fillRect(0, y, width, stripeHeight);
        }
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 40));
        g.drawString("STRIPED TEXT", 40, 400);
        g.dispose();
        byte[] png = TestDocuments.encode(image, "png");

        long start = System.nanoTime();
        parser.parse(request("striped.png", "image/png", png));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // One whole-image dual pass should take low single-digit seconds; dozens of per-band passes
        // would not. A generous bound (not a tight performance assertion) that still catches a real blowup.
        assertThat(elapsedMs).as("must fall back to a single pass, not OCR dozens of narrow bands individually")
                .isLessThan(15_000);
    }

    @Test
    void corruptImageFails() {
        assertThatThrownBy(() -> parser.parse(request("bad.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(DocumentExtractionException.class);
    }

    @Test
    void absurdlyLargeCanvasIsRejectedBeforeItIsDecoded() throws Exception {
        // 8,001 x 8,001 = 64 million pixels, but a blank 1-bit PNG is only a few KB: a decompression-bomb shape.
        BufferedImage huge = new BufferedImage(8001, 8001, BufferedImage.TYPE_BYTE_BINARY);
        byte[] png = TestDocuments.encode(huge, "png");
        assertThat(png.length).isLessThan(2_000_000);
        assertThatThrownBy(() -> parser.parse(request("bomb.png", "image/png", png)))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("too large");
    }
}
