package com.synapsemcp.ingestion.extraction.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextAndHtmlParsersTest {

    private final TextDocumentParser text = new TextDocumentParser();
    private final JsoupHtmlDocumentParser html = new JsoupHtmlDocumentParser();

    private String parseText(byte[] bytes) {
        return text.parse(new ExtractionRequest("a.txt", FileCategory.TEXT, "text/plain", bytes)).markdown();
    }

    private String parseHtml(String source) {
        return parseHtml(source.getBytes(StandardCharsets.UTF_8));
    }

    private String parseHtml(byte[] bytes) {
        return html.parse(new ExtractionRequest("a.html", FileCategory.HTML, "text/html", bytes)).markdown();
    }

    // ---------------- text ----------------

    @Test
    void utf8TextIsKeptAsIsIncludingJsonIndentationAndCsvRows() {
        String json = "{\n  \"name\": \"Zoë\",\n  \"items\": [1, 2]\n}";
        assertThat(parseText(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(json);
        String csv = "id,name\n1,Asha\n2,Ravi";
        assertThat(parseText(csv.getBytes(StandardCharsets.UTF_8))).isEqualTo(csv);
    }

    @Test
    void byteOrderMarksAreHonouredAndRemoved() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "héllo".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, 3);
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertThat(parseText(withBom)).isEqualTo("héllo");

        byte[] utf16 = "héllo wörld".getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16WithBom = new byte[utf16.length + 2];
        utf16WithBom[0] = (byte) 0xFF;
        utf16WithBom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, utf16WithBom, 2, utf16.length);
        assertThat(parseText(utf16WithBom)).isEqualTo("héllo wörld");
    }

    @Test
    void legacyWindows1252FilesAreDecodedInsteadOfFailing() {
        byte[] bytes = "café,naïve".getBytes(Charset.forName("windows-1252"));
        assertThat(parseText(bytes)).isEqualTo("café,naïve");
    }

    @Test
    void lineEndingsAreNormalizedAndControlCharactersDropped() {
        assertThat(parseText("a\r\nb\rc\u0001d\n\n\n\ne".getBytes(StandardCharsets.UTF_8))).isEqualTo("a\nb\ncd\n\ne");
    }

    @Test
    void binaryContentIsRejected() {
        assertThatThrownBy(() -> parseText(new byte[] {'a', 0, 'b', 0, 1, 2}))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("NUL");
    }

    // ---------------- html ----------------

    @Test
    void htmlScriptsStylesAndHiddenEmbedsAreRemoved() {
        String md = parseHtml("""
                <html><head><title>Page Title</title><style>.x{color:red}</style>
                <script>var secret = 'TRACKING-123';</script></head>
                <body><noscript>enable js</noscript><iframe src="x">frame text</iframe>
                <p>Visible paragraph.</p><script>alert('x')</script></body></html>
                """);
        assertThat(md).contains("Visible paragraph.").contains("# Page Title");
        assertThat(md).doesNotContain("TRACKING-123").doesNotContain("color:red").doesNotContain("alert")
                .doesNotContain("enable js").doesNotContain("frame text");
    }

    @Test
    void htmlHeadingsKeepTheirLevelAndTitleIsNotDuplicatedWhenAnH1Exists() {
        String md = parseHtml("<html><head><title>Doc</title></head><body><h1>Main</h1><h2>Sub</h2><h3>Deep</h3>"
                + "<p>Text</p></body></html>");
        assertThat(md).contains("# Main").contains("## Sub").contains("### Deep").contains("Text");
        assertThat(md).doesNotContain("# Doc");
    }

    @Test
    void htmlListsTablesPreAndImagesKeepStructure() {
        String md = parseHtml("""
                <body>
                <ul><li>Apples<ul><li>Green</li></ul></li><li>Pears</li></ul>
                <ol><li>First</li><li>Second</li></ol>
                <table><tr><th>Name</th><th>Qty</th></tr><tr><td>Bolt | M6</td><td>10</td></tr></table>
                <pre>line1
                  line2</pre>
                <p>See <a href="https://example.com">the docs</a> and <b>bold</b> text.</p>
                <img src="a.png" alt="Company logo">
                </body>
                """);
        assertThat(md).contains("- Apples").contains("  - Green").contains("- Pears");
        assertThat(md).contains("1. First").contains("2. Second");
        assertThat(md).contains("| Name | Qty |").contains("| --- | --- |").contains("| Bolt \\| M6 | 10 |");
        assertThat(md).contains("```\nline1\n  line2\n```");
        assertThat(md).contains("See the docs and bold text.");
        assertThat(md).contains("[Image: Company logo]");
    }

    @Test
    void htmlCharsetIsDetectedFromTheMetaTag() {
        byte[] latin1 = "<html><head><meta charset=\"ISO-8859-1\"></head><body><p>café crème</p></body></html>"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThat(parseHtml(latin1)).contains("café crème");
    }

    @Test
    void htmlWithNoVisibleTextProducesAnEmptyResultForTheRouterToReject() {
        assertThat(parseHtml("<html><head><script>x()</script></head><body></body></html>")).isEmpty();
    }
}
