package com.synapsemcp.ingestion.extraction.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parses files authored by real Microsoft Word, Excel and PowerPoint (src/test/resources/fixtures), in both the
 * modern and the legacy binary formats.
 */
class OfficeParsersTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = OfficeParsersTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static ExtractionRequest request(String name, FileCategory category) throws IOException {
        return new ExtractionRequest(name, category, "application/octet-stream", fixture(name));
    }

    private static boolean hasRow(String markdown, String... cells) {
        return markdown.lines().anyMatch(line -> line.startsWith("|") && java.util.Arrays.stream(cells).allMatch(line::contains));
    }

    // ---------------- Word ----------------

    @ParameterizedTest
    @ValueSource(strings = {"sample.docx", "sample.doc"})
    void wordKeepsHeadingsParagraphsListsAndTables(String file) throws Exception {
        String md = new PoiWordDocumentParser().parse(request(file, FileCategory.WORD)).markdown();
        System.out.println("[" + file + "]\n" + md + "\n");

        assertThat(md).contains("# Quarterly Report ALPHA-111");
        assertThat(md).contains("# Overview HEAD1-222");
        assertThat(md).contains("## Details HEAD2-666");
        assertThat(md).contains("Body text paragraph BODY-333.");
        assertThat(md).contains("Closing line END-888");
        assertThat(hasRow(md, "Item", "Qty", "Price")).isTrue();
        assertThat(hasRow(md, "Widget", "12", "3.50")).isTrue();
        assertThat(hasRow(md, "Gadget", "7", "19.99")).isTrue();
        assertThat(md).contains("| --- | --- | --- |");
        assertThat(md).doesNotContain("\u0007").doesNotContain("\u0013").doesNotContain("\u0015");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample.docx", "sample.doc"})
    void wordBulletsBecomeMarkdownListItems(String file) throws Exception {
        String md = new PoiWordDocumentParser().parse(request(file, FileCategory.WORD)).markdown();
        assertThat(md).contains("- Bullet one BUL-444").contains("- Bullet two BUL-555");
        // consecutive items stay in one tight list (no blank line between them)
        assertThat(md).contains("- Bullet one BUL-444\n- Bullet two BUL-555");
    }

    @Test
    void docKeepsBulletTextInOrder() throws Exception {
        String md = new PoiWordDocumentParser().parse(request("sample.doc", FileCategory.WORD)).markdown();
        assertThat(md).contains("Bullet one BUL-444").contains("Bullet two BUL-555");
        assertThat(md.indexOf("Overview HEAD1-222")).isLessThan(md.indexOf("Bullet one BUL-444"));
        assertThat(md.indexOf("Bullet two BUL-555")).isLessThan(md.indexOf("Details HEAD2-666"));
    }

    @Test
    void wordRejectsAFileThatIsNotWord() {
        ExtractionRequest bogus = new ExtractionRequest("fake.docx", FileCategory.WORD, "application/octet-stream",
                "this is not a word document".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new PoiWordDocumentParser().parse(bogus))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("fake.docx");
    }

    @Test
    void wordDetectsTheFormatFromContentNotTheExtension() throws Exception {
        ExtractionRequest docxNamedDoc = new ExtractionRequest("really-docx.doc", FileCategory.WORD,
                "application/msword", fixture("sample.docx"));
        assertThat(new PoiWordDocumentParser().parse(docxNamedDoc).markdown()).contains("ALPHA-111");
    }

    // ---------------- Excel ----------------

    @ParameterizedTest
    @ValueSource(strings = {"sample.xlsx", "sample.xls"})
    void excelSheetsBecomeMarkdownTablesWithEvaluatedFormulasAndFormats(String file) throws Exception {
        String md = new PoiExcelDocumentParser().parse(request(file, FileCategory.EXCEL)).markdown();
        System.out.println("[" + file + "]\n" + md + "\n");

        assertThat(md).contains("## Sales").contains("## Notes");
        assertThat(hasRow(md, "Item", "Qty", "Price", "Total")).isTrue();
        assertThat(md).contains("| --- | --- | --- | --- |");
        assertThat(hasRow(md, "Widget", "12", "3.5", "42")).isTrue();          // =B2*C2 evaluated
        assertThat(hasRow(md, "Gadget", "7", "19.99", "139.93")).isTrue();      // =B3*C3 evaluated
        assertThat(md).contains("Pipe\\|Name");                                  // pipe escaped, row not broken
        // shown the way Excel displays it (the file stores its own date format), never as the raw serial 46095
        assertThat(md.lines().anyMatch(l -> l.startsWith("| Date") && l.matches(".*2026[-/]03[-/]14.*"))).isTrue();
        assertThat(md).doesNotContain("46095");
        assertThat(hasRow(md, "Rate", "12.5%")).isTrue();                        // displayed as formatted, not 0.125
        assertThat(hasRow(md, "Topic", "Comment")).isTrue();
        assertThat(hasRow(md, "Budget", "Approved NOTE-XL-999")).isTrue();
        assertThat(md.indexOf("## Sales")).isLessThan(md.indexOf("## Notes"));
    }

    @Test
    void excelRejectsAFileThatIsNotASpreadsheet() {
        ExtractionRequest bogus = new ExtractionRequest("fake.xlsx", FileCategory.EXCEL, "application/octet-stream",
                "nope".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new PoiExcelDocumentParser().parse(bogus))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("fake.xlsx");
    }

    // ---------------- PowerPoint ----------------

    @ParameterizedTest
    @ValueSource(strings = {"sample.pptx", "sample.ppt"})
    void powerPointHasSlideHeadingsBulletsTablesAndSpeakerNotes(String file) throws Exception {
        String md = new PoiPptDocumentParser().parse(request(file, FileCategory.POWERPOINT)).markdown();
        System.out.println("[" + file + "]\n" + md + "\n");

        assertThat(md).contains("## Slide 1: Roadmap SLIDE-111");
        assertThat(md).contains("Launch in Q3 PPT-AAA").contains("Hire two engineers PPT-BBB");
        assertThat(md).contains("Speaker note NOTE-777");
        assertThat(md).contains("## Slide 2: Budget SLIDE-222");
        if (file.endsWith(".pptx")) {
            assertThat(hasRow(md, "Team", "Cost")).isTrue();
            assertThat(hasRow(md, "Platform", "4200")).isTrue();
        } else {
            // Known limitation: PowerPoint 97-2003 stores a "table" as loose text boxes, not a table object, so
            // the cell texts are kept (in order) but the row/column structure is not recoverable.
            assertThat(md).contains("Team").contains("Cost").contains("Platform").contains("4200");
        }
        assertThat(md.indexOf("Slide 1")).isLessThan(md.indexOf("Slide 2"));
        // the speaker note belongs to slide 1, so it must come before slide 2
        assertThat(md.indexOf("NOTE-777")).isLessThan(md.indexOf("## Slide 2"));
        // the slide title must not be repeated as body text
        assertThat(md.split("Roadmap SLIDE-111", -1)).hasSize(2);
    }

    @Test
    void powerPointRejectsAFileThatIsNotAPresentation() {
        ExtractionRequest bogus = new ExtractionRequest("fake.pptx", FileCategory.POWERPOINT,
                "application/octet-stream", "nope".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new PoiPptDocumentParser().parse(bogus))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("fake.pptx");
    }
}
