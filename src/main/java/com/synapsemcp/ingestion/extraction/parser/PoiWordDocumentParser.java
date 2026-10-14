package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.springframework.stereotype.Component;

/**
 * .docx (XWPF) and legacy .doc (HWPF), in document order: headings by style, bullets for list paragraphs, and
 * tables as Markdown tables. The format is detected from the file's magic bytes, not its extension.
 */
@Component
public class PoiWordDocumentParser implements DocumentParserStrategy {

    private static final Pattern HEADING_STYLE = Pattern.compile("heading\\s*(\\d)");
    // Word field codes (e.g. HYPERLINK "..."), delimited by 0x13 ... 0x14 (result follows) or 0x13 ... 0x15 (no result).
    private static final Pattern FIELD_WITHOUT_RESULT = Pattern.compile("\u0013[^\u0013\u0014\u0015]*\u0015");
    private static final Pattern FIELD_CODE = Pattern.compile("\u0013[^\u0013\u0014\u0015]*\u0014");

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.WORD;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        try {
            FileMagic magic = FileMagic.valueOf(new ByteArrayInputStream(request.content()));
            String markdown = switch (magic) {
                case OOXML -> parseDocx(request.content());
                case OLE2 -> parseDoc(request.content());
                default -> throw new DocumentExtractionException(
                        "Not a valid Word document (.doc/.docx): " + request.filename());
            };
            return new ExtractionResult(MarkdownSupport.normalize(markdown));
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof DocumentExtractionException dee) {
                throw dee;
            }
            throw new DocumentExtractionException("Could not read Word document " + request.filename() + ": "
                    + ex.getMessage(), ex);
        }
    }

    private String parseDocx(byte[] content) throws IOException {
        StringBuilder out = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(doc, paragraph, out);
                } else if (element instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText());
                        }
                        rows.add(cells);
                    }
                    out.append(MarkdownSupport.table(rows)).append('\n');
                }
            }
        }
        return out.toString();
    }

    private void appendParagraph(XWPFDocument doc, XWPFParagraph paragraph, StringBuilder out) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String styleName = styleName(doc, paragraph.getStyleID());
        int level = headingLevel(styleName);
        if (level > 0) {
            out.append(MarkdownSupport.heading(level, text)).append("\n\n");
            return;
        }
        ListInfo list = listInfo(doc, paragraph, styleName);
        if (list == null) {
            out.append(text.strip()).append("\n\n");
        } else {
            out.append("  ".repeat(list.level())).append(list.ordered() ? "1. " : "- ").append(text.strip()).append('\n');
        }
    }

    private record ListInfo(boolean ordered, int level) {
    }

    /**
     * Word attaches list numbering either directly to the paragraph or through its style (e.g. "List Bullet"), so
     * both are checked. Returns null for a paragraph that is not a list item.
     */
    private ListInfo listInfo(XWPFDocument doc, XWPFParagraph paragraph, String styleName) {
        BigInteger numId = paragraph.getNumID();
        BigInteger ilvl = paragraph.getNumIlvl();
        XWPFStyle style = paragraph.getStyleID() == null || doc.getStyles() == null
                ? null : doc.getStyles().getStyle(paragraph.getStyleID());
        if (numId == null && style != null && style.getCTStyle().isSetPPr() && style.getCTStyle().getPPr().isSetNumPr()) {
            CTNumPr numPr = style.getCTStyle().getPPr().getNumPr();
            if (numPr.isSetNumId()) {
                numId = numPr.getNumId().getVal();
            }
            if (ilvl == null && numPr.isSetIlvl()) {
                ilvl = numPr.getIlvl().getVal();
            }
        }
        String name = styleName.toLowerCase(Locale.ROOT);
        boolean namedList = name.startsWith("list bullet") || name.startsWith("list number");
        if (numId == null && !namedList) {
            return null;
        }
        int depth = ilvl == null ? 0 : ilvl.intValue();
        return new ListInfo(isOrdered(doc, numId, depth, name), depth);
    }

    private static boolean isOrdered(XWPFDocument doc, BigInteger numId, int level, String styleName) {
        XWPFNumbering numbering = doc.getNumbering();
        if (numId != null && numbering != null && numbering.getNum(numId) != null) {
            BigInteger abstractId = numbering.getNum(numId).getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abstractNum = numbering.getAbstractNum(abstractId);
            if (abstractNum != null && level < abstractNum.getCTAbstractNum().sizeOfLvlArray()) {
                CTLvl lvl = abstractNum.getCTAbstractNum().getLvlArray(level);
                if (lvl.getNumFmt() != null) {
                    STNumberFormat.Enum format = lvl.getNumFmt().getVal();
                    return format != STNumberFormat.BULLET && format != STNumberFormat.NONE;
                }
            }
        }
        return styleName.contains("number");
    }

    private static String styleName(XWPFDocument doc, String styleId) {
        if (styleId == null) {
            return "";
        }
        XWPFStyle style = doc.getStyles() == null ? null : doc.getStyles().getStyle(styleId);
        return style != null && style.getName() != null ? style.getName() : styleId;
    }

    static int headingLevel(String styleName) {
        String name = styleName == null ? "" : styleName.toLowerCase(java.util.Locale.ROOT);
        Matcher m = HEADING_STYLE.matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return name.equals("title") ? 1 : 0;
    }

    private String parseDoc(byte[] content) throws IOException {
        StringBuilder out = new StringBuilder();
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
            Range range = doc.getRange();
            int count = range.numParagraphs();
            int i = 0;
            while (i < count) {
                Paragraph paragraph = range.getParagraph(i);
                if (paragraph.isInTable()) {
                    Table table = range.getTable(paragraph);
                    List<List<String>> rows = new ArrayList<>();
                    for (int r = 0; r < table.numRows(); r++) {
                        TableRow row = table.getRow(r);
                        List<String> cells = new ArrayList<>();
                        for (int c = 0; c < row.numCells(); c++) {
                            TableCell cell = row.getCell(c);
                            cells.add(clean(cell.text()));
                        }
                        rows.add(cells);
                    }
                    out.append(MarkdownSupport.table(rows)).append('\n');
                    int end = table.getEndOffset();
                    while (i < count && range.getParagraph(i).getStartOffset() < end) {
                        i++;
                    }
                    continue;
                }
                i++;
                String text = clean(paragraph.text());
                if (text.isBlank()) {
                    continue;
                }
                StyleDescription style = doc.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
                String styleName = style == null || style.getName() == null ? "" : style.getName();
                int level = headingLevel(styleName);
                String lower = styleName.toLowerCase(Locale.ROOT);
                if (level > 0) {
                    out.append(MarkdownSupport.heading(level, text)).append("\n\n");
                } else if (paragraph.isInList() || lower.startsWith("list bullet") || lower.startsWith("list number")) {
                    out.append(lower.contains("number") ? "1. " : "- ").append(text.strip()).append('\n');
                } else {
                    out.append(text.strip()).append("\n\n");
                }
            }
        }
        return out.toString();
    }

    /** Strips Word's in-band control characters (cell/row marks, field codes, picture markers). */
    static String clean(String raw) {
        String s = FIELD_WITHOUT_RESULT.matcher(raw).replaceAll("");
        s = FIELD_CODE.matcher(s).replaceAll("");
        return s.replaceAll("[\u0001\u0007\u0008\u0013\u0014\u0015]", "");
    }
}
