package com.synapsemcp.ingestion.extraction.parser;

import java.util.List;
import java.util.regex.Pattern;

/** Shared Markdown helpers for the Tier 2 parsers. */
public final class MarkdownSupport {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+(?=\\n)");
    private static final Pattern BLANK_RUNS = Pattern.compile("\\n{3,}");

    private MarkdownSupport() {
    }

    /**
     * Renders rows as a Markdown table. The first row becomes the header. Short rows are padded so every row has
     * the same number of cells. Returns an empty string when there is nothing to render.
     */
    static String table(List<List<String>> rows) {
        int width = 0;
        for (List<String> row : rows) {
            width = Math.max(width, row.size());
        }
        if (rows.isEmpty() || width == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            sb.append('|');
            List<String> row = rows.get(r);
            for (int c = 0; c < width; c++) {
                sb.append(' ').append(cell(c < row.size() ? row.get(c) : "")).append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                sb.append(" --- |".repeat(width));
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Escapes a table cell: pipes would break the row, line breaks would end it. */
    static String cell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace("|", "\\|").strip();
    }

    /**
     * Makes extracted text safe and tidy for storage: no NUL/control characters (PostgreSQL text cannot hold NUL),
     * Unix line endings, no trailing spaces, and at most one blank line in a row.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');
        s = CONTROL_CHARS.matcher(s).replaceAll("");
        s = TRAILING_SPACES.matcher(s).replaceAll("");
        s = BLANK_RUNS.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    static String heading(int level, String text) {
        return "#".repeat(Math.max(1, Math.min(6, level))) + " " + text.strip();
    }
}
