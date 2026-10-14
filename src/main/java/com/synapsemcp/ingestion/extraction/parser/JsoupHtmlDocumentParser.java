package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Cleans the DOM (scripts, styles, embeds) and converts the rest to Markdown: headings keep their level, lists and
 * tables keep their structure, and link/image text is kept. The charset is detected from the BOM or meta tag.
 */
@Component
public class JsoupHtmlDocumentParser implements DocumentParserStrategy {

    private static final String REMOVED = "script, style, noscript, template, iframe, object, embed, svg, canvas, "
            + "audio, video, link, meta, head > *:not(title), form input, form select, form button, form textarea";
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "header", "footer", "main",
            "aside", "nav", "figure", "figcaption", "address", "details", "summary", "dl", "dt", "dd", "form",
            "fieldset", "body");

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.HTML;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(request.content()), null, "");
        } catch (IOException ex) {
            throw new DocumentExtractionException("Could not read HTML " + request.filename() + ": " + ex.getMessage(), ex);
        }
        doc.select(REMOVED).remove();

        StringBuilder out = new StringBuilder();
        String title = doc.title().strip();
        if (!title.isEmpty() && doc.body().selectFirst("h1") == null) {
            out.append(MarkdownSupport.heading(1, title)).append("\n\n");
        }
        render(doc.body(), out, 0);
        return new ExtractionResult(MarkdownSupport.normalize(out.toString()));
    }

    private void render(Element element, StringBuilder out, int listDepth) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode text) {
                String t = text.text();
                if (!t.isBlank()) {
                    appendInline(out, t);
                }
            } else if (child instanceof Element el) {
                renderElement(el, out, listDepth);
            }
        }
    }

    private void renderElement(Element el, StringBuilder out, int listDepth) {
        String tag = el.normalName();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = el.text().strip();
                if (!text.isEmpty()) {
                    blank(out);
                    out.append(MarkdownSupport.heading(tag.charAt(1) - '0', text));
                    blank(out);
                }
            }
            case "ul", "ol" -> renderList(el, out, listDepth);
            case "table" -> renderTable(el, out);
            case "pre" -> {
                blank(out);
                out.append("```\n").append(el.wholeText().strip()).append("\n```");
                blank(out);
            }
            case "br" -> out.append('\n');
            case "hr" -> {
                blank(out);
                out.append("---");
                blank(out);
            }
            case "img" -> {
                String alt = el.attr("alt").strip();
                if (!alt.isEmpty()) {
                    appendInline(out, "[Image: " + alt + "]");
                }
            }
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                render(el, inner, listDepth);
                blank(out);
                for (String line : MarkdownSupport.normalize(inner.toString()).split("\n", -1)) {
                    out.append("> ").append(line).append('\n');
                }
                blank(out);
            }
            case "p" -> {
                blank(out);
                render(el, out, listDepth);
                blank(out);
            }
            default -> {
                if (BLOCKS.contains(tag)) {
                    blank(out);
                    render(el, out, listDepth);
                    blank(out);
                } else {
                    render(el, out, listDepth); // inline: a, span, b, i, em, strong, code, ...
                }
            }
        }
    }

    private void renderList(Element list, StringBuilder out, int depth) {
        boolean ordered = list.normalName().equals("ol");
        int index = 1;
        blank(out);
        for (Element item : list.children()) {
            if (!item.normalName().equals("li")) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            List<Element> nested = new ArrayList<>();
            for (Node n : item.childNodes()) {
                if (n instanceof Element e && (e.normalName().equals("ul") || e.normalName().equals("ol"))) {
                    nested.add(e);
                } else if (n instanceof TextNode t) {
                    appendInline(body, t.text());
                } else if (n instanceof Element e) {
                    renderElement(e, body, depth + 1);
                }
            }
            String line = MarkdownSupport.normalize(body.toString()).replace("\n", " ");
            out.append("  ".repeat(depth)).append(ordered ? index++ + ". " : "- ").append(line).append('\n');
            for (Element n : nested) {
                renderList(n, out, depth + 1);
            }
        }
        blank(out);
    }

    private void renderTable(Element table, StringBuilder out) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            if (tr.closest("table") != table) {
                continue; // belongs to a nested table
            }
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                    cells.add(cell.text());
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        String markdown = MarkdownSupport.table(rows);
        if (!markdown.isEmpty()) {
            blank(out);
            out.append(markdown);
            blank(out);
        }
    }

    private static void appendInline(StringBuilder out, String text) {
        String collapsed = text.replaceAll("\\s+", " ");
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ' && collapsed.startsWith(" ")) {
            collapsed = collapsed.substring(1);
        }
        if ((out.length() == 0 || out.charAt(out.length() - 1) == '\n') && collapsed.startsWith(" ")) {
            collapsed = collapsed.substring(1);
        }
        out.append(collapsed);
    }

    /** Ensures the output ends with exactly one blank line (or is empty), so blocks never run together. */
    private static void blank(StringBuilder out) {
        int len = out.length();
        if (len == 0) {
            return;
        }
        while (len > 0 && (out.charAt(len - 1) == ' ')) {
            out.setLength(--len);
        }
        if (len >= 2 && out.charAt(len - 1) == '\n' && out.charAt(len - 2) == '\n') {
            return;
        }
        out.append(len >= 1 && out.charAt(len - 1) == '\n' ? "\n" : "\n\n");
    }
}
