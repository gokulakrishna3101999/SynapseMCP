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
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Notes;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.PlaceholderDetails;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextRun;
import org.apache.poi.sl.usermodel.TextShape;
import org.springframework.stereotype.Component;

/**
 * .pptx and .ppt, slide by slide: a "## Slide N: title" heading, the slide's text (bulleted where the slide
 * bulleted it), tables as Markdown tables, and the speaker notes under a "Notes:" label.
 */
@Component
public class PoiPptDocumentParser implements DocumentParserStrategy {

    private static final Set<Placeholder> NON_CONTENT = Set.of(Placeholder.SLIDE_IMAGE, Placeholder.SLIDE_NUMBER,
            Placeholder.HEADER, Placeholder.FOOTER, Placeholder.DATETIME);

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.POWERPOINT;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        StringBuilder out = new StringBuilder();
        try (SlideShow<?, ?> show = SlideShowFactory.create(new ByteArrayInputStream(request.content()))) {
            int number = 1;
            for (Slide<?, ?> slide : show.getSlides()) {
                String title = slide.getTitle() == null ? "" : MarkdownSupport.normalize(slide.getTitle());
                // Body built separately and only committed if the slide actually has a title or content: a
                // slide with nothing but empty/decorative shapes (e.g. an unused text box, image-only slides)
                // must not emit a heading-only stub like "## Slide 3" with nothing under it - that would be
                // non-blank content with zero informational value, silently bypassing the "no readable text"
                // check below and polluting the knowledge base with content-free noise.
                StringBuilder body = new StringBuilder();
                for (Shape<?, ?> shape : slide.getShapes()) {
                    appendShape(shape, title, body);
                }
                Notes<?, ?> notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder noteText = new StringBuilder();
                    for (Shape<?, ?> shape : notes.getShapes()) {
                        appendShape(shape, "", noteText);
                    }
                    String text = MarkdownSupport.normalize(noteText.toString());
                    if (!text.isEmpty()) {
                        body.append("**Notes:** ").append(text.replace("\n\n", "\n")).append("\n\n");
                    }
                }
                int slideNumber = number++;
                if (title.isEmpty() && body.toString().isBlank()) {
                    continue;
                }
                out.append("## Slide ").append(slideNumber).append(title.isEmpty() ? "" : ": " + title)
                        .append("\n\n").append(body);
            }
        } catch (IOException | RuntimeException ex) {
            throw new DocumentExtractionException("Could not read presentation " + request.filename() + ": "
                    + ex.getMessage(), ex);
        }
        return new ExtractionResult(MarkdownSupport.normalize(out.toString()));
    }

    private void appendShape(Shape<?, ?> shape, String slideTitle, StringBuilder out) {
        if (shape instanceof GroupShape<?, ?> group) {
            for (Shape<?, ?> child : group) {
                appendShape(child, slideTitle, out);
            }
        } else if (shape instanceof TableShape<?, ?> table) {
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < table.getNumberOfRows(); r++) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < table.getNumberOfColumns(); c++) {
                    var cell = table.getCell(r, c);
                    cells.add(cell == null || cell.getText() == null ? "" : cell.getText());
                }
                rows.add(cells);
            }
            out.append(MarkdownSupport.table(rows)).append('\n');
        } else if (shape instanceof TextShape<?, ?> text) {
            if (isNonContent(text)) {
                return;
            }
            boolean first = true;
            for (TextParagraph<?, ?, ?> paragraph : text.getTextParagraphs()) {
                StringBuilder line = new StringBuilder();
                for (TextRun run : paragraph.getTextRuns()) {
                    line.append(run.getRawText());
                }
                String value = line.toString().replace('\u000B', ' ').strip();
                if (value.isEmpty() || (first && value.equals(slideTitle))) {
                    first = false;
                    continue;
                }
                first = false;
                if (paragraph.getBulletStyle() != null) {
                    out.append("  ".repeat(Math.max(0, paragraph.getIndentLevel()))).append("- ").append(value).append('\n');
                } else {
                    out.append(value).append("\n\n");
                }
            }
            out.append('\n');
        }
    }

    private static boolean isNonContent(TextShape<?, ?> shape) {
        PlaceholderDetails details = shape.getPlaceholderDetails();
        return details != null && details.getPlaceholder() != null && NON_CONTENT.contains(details.getPlaceholder());
    }
}
