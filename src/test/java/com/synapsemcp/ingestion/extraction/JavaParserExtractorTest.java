package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.parser.JsoupHtmlDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.PdfBoxDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.PoiExcelDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.PoiPptDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.PoiWordDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.TesseractOcrDocumentParser;
import com.synapsemcp.ingestion.extraction.parser.TesseractOcrEngine;
import com.synapsemcp.ingestion.extraction.parser.TextDocumentParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

/** The router itself, plus the PDF parser (which needs generated PDFs from {@link TestDocuments}). */
class JavaParserExtractorTest {

    private static final TesseractOcrEngine OCR_ENGINE = new TesseractOcrEngine();
    private static final List<DocumentParserStrategy> ALL_PARSERS = List.of(new PdfBoxDocumentParser(OCR_ENGINE),
            new PoiWordDocumentParser(), new PoiExcelDocumentParser(), new PoiPptDocumentParser(),
            new JsoupHtmlDocumentParser(), new TextDocumentParser(), new TesseractOcrDocumentParser(OCR_ENGINE));

    private final JavaParserExtractor extractor = new JavaParserExtractor(ALL_PARSERS);

    private static ExtractionRequest pdf(byte[] bytes) {
        return new ExtractionRequest("a.pdf", FileCategory.PDF, "application/pdf", bytes);
    }

    @Test
    void everyFileCategoryHasExactlyOneParser() {
        for (FileCategory category : FileCategory.values()) {
            long matches = ALL_PARSERS.stream().filter(p -> p.supports(category)).count();
            assertThat(matches).as(category.name()).isEqualTo(1);
        }
    }

    @Test
    void routesToTheParserForTheCategory() {
        ExtractionRequest request = new ExtractionRequest("a.md", FileCategory.TEXT, "text/markdown",
                "# Hello".getBytes(StandardCharsets.UTF_8));
        assertThat(extractor.extract(request).markdown()).isEqualTo("# Hello");
    }

    @Test
    void emptyAndBlankFilesFailTheJob() {
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("a.txt", FileCategory.TEXT, "text/plain",
                new byte[0]))).isInstanceOf(DocumentExtractionException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("a.txt", FileCategory.TEXT, "text/plain",
                "  \n\t \n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("No readable text");
    }

    @Test
    void unexpectedParserErrorsAreWrapped() {
        DocumentParserStrategy exploding = new DocumentParserStrategy() {
            @Override
            public boolean supports(FileCategory category) {
                return true;
            }

            @Override
            public ExtractionResult parse(ExtractionRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        assertThatThrownBy(() -> new JavaParserExtractor(List.of(exploding)).extract(pdf(new byte[] {1})))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("boom");
    }

    @Test
    void aCategoryWithNoParserFails() {
        assertThatThrownBy(() -> new JavaParserExtractor(List.of(new TextDocumentParser())).extract(pdf(new byte[] {1})))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("No parser");
    }

    // ---------------- PDF ----------------

    @Test
    void pdfTextIsExtractedPageByPageInOrder() {
        List<TestDocuments.Page> pages = Arrays.asList(
                TestDocuments.Page.text(TestDocuments.textLines(14, 720, List.of("Chapter 1", "Marker MARK-1-1111"))),
                TestDocuments.Page.text(TestDocuments.textLines(14, 720, List.of("Chapter 2", "Marker MARK-2-2222"))),
                TestDocuments.Page.text(TestDocuments.textLines(14, 720, List.of("Chapter 3", "Marker MARK-3-3333"))));
        String md = extractor.extract(pdf(TestDocuments.pdf(pages))).markdown();
        System.out.println("[pdf]\n" + md);
        assertThat(md).contains("Chapter 1").contains("MARK-1-1111").contains("MARK-2-2222").contains("MARK-3-3333");
        assertThat(md.indexOf("MARK-1-1111")).isLessThan(md.indexOf("MARK-2-2222"));
        assertThat(md.indexOf("MARK-2-2222")).isLessThan(md.indexOf("MARK-3-3333"));
    }

    @Test
    void pdfTableTextKeepsEachRowOnItsOwnLine() {
        String[][] rows = {{"Item", "Qty", "Price"}, {"Widget", "12", "3.50"}, {"Gadget", "7", "19.99"}};
        String md = extractor.extract(pdf(TestDocuments.pdf(List.of(
                TestDocuments.Page.text(TestDocuments.tablePage("Price List", rows)))))).markdown();
        System.out.println("[pdf table]\n" + md);
        assertThat(md.lines().anyMatch(l -> l.contains("Widget") && l.contains("12") && l.contains("3.50"))).isTrue();
        assertThat(md.lines().anyMatch(l -> l.contains("Gadget") && l.contains("7") && l.contains("19.99"))).isTrue();
    }

    @Test
    void scannedPdfWithoutATextLayerIsReadViaOcr() throws Exception {
        byte[] jpeg = TestDocuments.encode(TestDocuments.textImage(612, 792, 40, List.of("SCANNED ONLY")), "jpg");
        byte[] scanned = TestDocuments.pdf(List.of(TestDocuments.Page.scan(jpeg, 612, 792)));
        String md = extractor.extract(pdf(scanned)).markdown();
        System.out.println("[pdf ocr] " + md);
        assertThat(md).contains("SCANNED ONLY");
    }

    @Test
    void mixedPdfOnlyOcrsThePagesThatNeedIt() throws Exception {
        byte[] jpeg = TestDocuments.encode(TestDocuments.textImage(612, 792, 40, List.of("SECOND PAGE SCAN")), "jpg");
        byte[] mixed = TestDocuments.pdf(List.of(
                TestDocuments.Page.text(TestDocuments.tablePage("Cover Page", new String[][] {{"Real", "Text", "Here"}})),
                TestDocuments.Page.scan(jpeg, 612, 792)));
        String md = extractor.extract(pdf(mixed)).markdown();
        System.out.println("[pdf mixed] " + md);
        assertThat(md).contains("Cover Page").contains("SECOND PAGE SCAN");
    }

    @Test
    void scannedPdfWithNoLegibleTextFailsWithAClearMessage() throws Exception {
        java.awt.image.BufferedImage blank = new java.awt.image.BufferedImage(612, 792,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = blank.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 612, 792);
        g.dispose();
        byte[] jpeg = TestDocuments.encode(blank, "jpg");
        byte[] scanned = TestDocuments.pdf(List.of(TestDocuments.Page.scan(jpeg, 612, 792)));
        assertThatThrownBy(() -> extractor.extract(pdf(scanned)))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("No readable text found");
    }

    @Test
    void passwordProtectedPdfFailsWithAClearMessage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
        }
        assertThatThrownBy(() -> extractor.extract(pdf(out.toByteArray())))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("password");
    }

    @Test
    void corruptPdfFailsTheJob() {
        assertThatThrownBy(() -> extractor.extract(pdf("%PDF-1.4 garbage".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(DocumentExtractionException.class);
    }
}
