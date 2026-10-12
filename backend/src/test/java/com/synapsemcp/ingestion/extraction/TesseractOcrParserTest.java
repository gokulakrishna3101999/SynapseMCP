package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.parser.TesseractOcrDocumentParser;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real Tesseract OCR (Tess4J native libraries + bundled English data) on generated images. */
class TesseractOcrParserTest {

    private final TesseractOcrDocumentParser parser = new TesseractOcrDocumentParser();

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
