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
import java.security.SecureRandom;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/**
 * Edge cases and malformed/boundary input for every Tier 2 parser, beyond what {@code OfficeParsersTest},
 * {@code TextAndHtmlParsersTest}, {@code TesseractOcrParserTest} and {@code JavaParserExtractorTest} already
 * cover. The point of this class is stability, not feature coverage: every case here must surface as a clean
 * {@link DocumentExtractionException} (or a correct markdown result) - never an uncaught exception, a hang, or
 * a crash - no matter how malformed or empty the real-world input is.
 */
class ParserEdgeCaseStabilityTest {

    private static final TesseractOcrEngine OCR_ENGINE = new TesseractOcrEngine();
    private static final List<DocumentParserStrategy> ALL_PARSERS = List.of(new PdfBoxDocumentParser(OCR_ENGINE),
            new PoiWordDocumentParser(), new PoiExcelDocumentParser(), new PoiPptDocumentParser(),
            new JsoupHtmlDocumentParser(), new TextDocumentParser(), new TesseractOcrDocumentParser(OCR_ENGINE));
    private final JavaParserExtractor extractor = new JavaParserExtractor(ALL_PARSERS);

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ---------------- PDF ----------------

    @Test
    void zeroPagePdfFailsWithAClearMessageInsteadOfCrashing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.save(out);
        }
        assertThatThrownBy(() -> extractor.extract(
                new ExtractionRequest("empty.pdf", FileCategory.PDF, "application/pdf", out.toByteArray())))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("No readable text");
    }

    @Test
    void pdfWithAccentedAndNonLatinUnicodeTextIsExtractedCorrectly() {
        // Helvetica (the only font embedded by TestDocuments) can only render Latin-1 glyphs, so this proves
        // the extraction *pipeline* survives non-ASCII bytes end to end without mangling or crashing, using
        // the accented characters Helvetica can actually render; true CJK text would need an embedded CJK font.
        byte[] pdf = TestDocuments.pdf(List.of(TestDocuments.Page.text(
                TestDocuments.textLines(14, 700, List.of("Café résumé naïve über")))));
        String markdown = extractor.extract(new ExtractionRequest("accents.pdf", FileCategory.PDF,
                "application/pdf", pdf)).markdown();
        assertThat(markdown).contains("Café", "résumé", "naïve", "über");
    }

    @Test
    void randomGarbageBytesNamedAsAPdfFailCleanlyNotWithAnUncaughtException() {
        assertThatThrownBy(() -> extractor.extract(
                new ExtractionRequest("garbage.pdf", FileCategory.PDF, "application/pdf", randomBytes(2048))))
                .isInstanceOf(DocumentExtractionException.class);
    }

    // ---------------- Word ----------------

    @Test
    void docxWithNoParagraphsAtAllFailsWithNoReadableText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.write(out);
        }
        assertThatThrownBy(() -> extractor.extract(
                new ExtractionRequest("blank.docx", FileCategory.WORD,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray())))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("No readable text");
    }

    @Test
    void corruptDocxFailsCleanlyNotWithAnUncaughtException() {
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("corrupt.docx", FileCategory.WORD,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", randomBytes(4096))))
                .isInstanceOf(DocumentExtractionException.class);
    }

    @Test
    void docxWithUnicodeAndEmojiTextRoundTripsExactly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Marker café 你好 😀 — done");
            doc.write(out);
        }
        String markdown = extractor.extract(new ExtractionRequest("unicode.docx", FileCategory.WORD,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray()))
                .markdown();
        assertThat(markdown).contains("café", "你好", "😀");
    }

    // ---------------- Excel ----------------

    @Test
    void emptyWorkbookWithNoRowsFailsWithNoReadableText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Empty");
            wb.write(out);
        }
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("empty.xlsx", FileCategory.EXCEL,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray())))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("No readable text");
    }

    @Test
    void errorFormulaCellsAndMergedCellsAreRenderedWithoutCrashing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Label");
            header.createCell(1).setCellValue("Value");
            Row merged = sheet.createRow(1);
            merged.createCell(0).setCellValue("Merged Title");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, 1));
            Row errorRow = sheet.createRow(2);
            errorRow.createCell(0).setCellValue("Bad Formula");
            Cell errorCell = errorRow.createCell(1);
            errorCell.setCellFormula("1/0");
            wb.write(out);
        }
        String markdown = extractor.extract(new ExtractionRequest("errors.xlsx", FileCategory.EXCEL,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()))
                .markdown();
        assertThat(markdown).contains("Merged Title", "Bad Formula");
    }

    @Test
    void corruptXlsxFailsCleanlyNotWithAnUncaughtException() {
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("corrupt.xlsx", FileCategory.EXCEL,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", randomBytes(4096))))
                .isInstanceOf(DocumentExtractionException.class);
    }

    @Test
    void legacyXlsWithDataIsStillReadable() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Legacy");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("LegacyMarker-XLS-204");
            wb.write(out);
        }
        String markdown = extractor.extract(new ExtractionRequest("legacy.xls", FileCategory.EXCEL,
                "application/vnd.ms-excel", out.toByteArray())).markdown();
        assertThat(markdown).contains("LegacyMarker-XLS-204");
    }

    // ---------------- PowerPoint ----------------

    @Test
    void slideWithOnlyAnEmptyTextBoxAndNoRealContentFailsWithNoReadableText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            slide.createTextBox(); // present on the slide, but never given any text
            ppt.write(out);
        }
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("blank.pptx", FileCategory.POWERPOINT,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", out.toByteArray())))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("No readable text");
    }

    @Test
    void corruptPptxFailsCleanlyNotWithAnUncaughtException() {
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("corrupt.pptx", FileCategory.POWERPOINT,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", randomBytes(4096))))
                .isInstanceOf(DocumentExtractionException.class);
    }

    // ---------------- HTML ----------------

    @Test
    void malformedUnclosedTagsStillYieldTheVisibleTextInstead() {
        String html = "<html><body><p>First paragraph<p>Second <b>bold without closing "
                + "<div>Nested <span>span</div> tail text";
        String markdown = extractor.extract(new ExtractionRequest("broken.html", FileCategory.HTML, "text/html",
                html.getBytes(StandardCharsets.UTF_8))).markdown();
        assertThat(markdown).contains("First paragraph", "Second", "bold without closing", "Nested", "span", "tail text");
    }

    @Test
    void htmlEntitiesAreDecodedNotLeftAsLiteralCodes() {
        String html = "<html><body><p>Fish &amp; Chips &lt;tag&gt; caf&eacute; &copy; 2026</p></body></html>";
        String markdown = extractor.extract(new ExtractionRequest("entities.html", FileCategory.HTML, "text/html",
                html.getBytes(StandardCharsets.UTF_8))).markdown();
        assertThat(markdown).contains("Fish & Chips <tag> café").contains("©");
        assertThat(markdown).doesNotContain("&amp;", "&lt;", "&eacute;");
    }

    @Test
    void deeplyNestedHtmlDoesNotStackOverflow() {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 500; i++) {
            html.append("<div>");
        }
        html.append("Deeply nested marker text");
        for (int i = 0; i < 500; i++) {
            html.append("</div>");
        }
        html.append("</body></html>");
        String markdown = extractor.extract(new ExtractionRequest("deep.html", FileCategory.HTML, "text/html",
                html.toString().getBytes(StandardCharsets.UTF_8))).markdown();
        assertThat(markdown).contains("Deeply nested marker text");
    }

    // ---------------- Text/CSV/JSON/Markdown ----------------

    @Test
    void aVeryLongSingleLineWithNoNewlinesIsHandledWithoutExcessiveSlowdown() {
        String longLine = "field,".repeat(50_000) + "MARKER-LONGLINE-918";
        long start = System.currentTimeMillis();
        String markdown = extractor.extract(new ExtractionRequest("long.csv", FileCategory.TEXT, "text/csv",
                longLine.getBytes(StandardCharsets.UTF_8))).markdown();
        long elapsedMs = System.currentTimeMillis() - start;
        assertThat(markdown).contains("MARKER-LONGLINE-918");
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void malformedJsonIsStillPassedThroughAsTextNotRejected() {
        // JSON/CSV/MD all share the generic TEXT category - this parser does not validate structure, only
        // decodes bytes, so a syntactically broken JSON file is not a parsing failure at this tier.
        String brokenJson = "{\"key\": \"value\", \"unterminated\": \"MARKER-BADJSON-447";
        String markdown = extractor.extract(new ExtractionRequest("broken.json", FileCategory.TEXT,
                "application/json", brokenJson.getBytes(StandardCharsets.UTF_8))).markdown();
        assertThat(markdown).contains("MARKER-BADJSON-447");
    }
}
