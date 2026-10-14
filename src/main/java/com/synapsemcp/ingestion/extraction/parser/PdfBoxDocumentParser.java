package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Text extraction from PDFs with their embedded text layer, in reading order. A page with no embedded
 * text (a scanned page, or an image dropped into an otherwise-text PDF) is rendered to an image and run
 * through the same {@link TesseractOcrEngine} used for standalone images, page by page - so a fully
 * scanned PDF gets every page OCR'd, and a mixed PDF only pays the OCR cost for the pages that actually
 * need it. Bounded by the file's overall 10MiB upload limit and {@code synapse.ingestion.tier2.timeout}
 * (the existing Tier 2 safety net already covers a pathologically slow multi-page OCR run - no separate
 * page-count or size cap was added here). Measured live on a real 6-page scanned PDF: ~2-5s/page under
 * normal load, well inside the 2-minute Tier 2 timeout - Tesseract is CPU-bound, so heavy concurrent
 * ingestion load on a CPU-constrained host can slow this down; that is host contention, not a defect here
 * (confirmed live: the identical file that once timed out completed in ~17s once nothing else was
 * competing for CPU, via diagnostic logging added and removed during that investigation).
 */
@Component
public class PdfBoxDocumentParser implements DocumentParserStrategy {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxDocumentParser.class);
    /** Good OCR accuracy/speed tradeoff for a rendered page; reduced per-page if it would exceed MAX_PIXELS. */
    private static final float TARGET_DPI = 200f;
    private static final float MIN_DPI = 72f;

    private final TesseractOcrEngine ocrEngine;

    public PdfBoxDocumentParser(TesseractOcrEngine ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.PDF;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        try (PDDocument document = Loader.loadPDF(request.content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            PDFRenderer renderer = new PDFRenderer(document);

            StringBuilder out = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = MarkdownSupport.normalize(stripper.getText(document));
                if (text.isEmpty()) {
                    text = ocrPage(renderer, document.getPage(page - 1), page, request);
                }
                if (!text.isEmpty()) {
                    out.append(text).append("\n\n");
                }
            }

            String markdown = MarkdownSupport.normalize(out.toString());
            if (markdown.isEmpty()) {
                throw new DocumentExtractionException("No readable text found in " + request.filename()
                        + " (scanned pages were OCR'd but nothing legible was found)");
            }
            return new ExtractionResult(markdown);
        } catch (InvalidPasswordException ex) {
            throw new DocumentExtractionException("The PDF is password-protected: " + request.filename(), ex);
        } catch (IOException ex) {
            throw new DocumentExtractionException("Could not read PDF " + request.filename() + ": " + ex.getMessage(), ex);
        }
    }

    /** A page that fails to render or OCR is skipped (logged), not fatal to the rest of the document. */
    private String ocrPage(PDFRenderer renderer, PDPage page, int pageNumber, ExtractionRequest request) {
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, dpiFor(page), ImageType.RGB);
            return ocrEngine.ocr(image, request.filename() + " page " + pageNumber);
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not OCR page {} of {}, skipping that page", pageNumber, request.filename(), ex);
            return "";
        }
    }

    /** Points (1/72in) -> pixels at TARGET_DPI; backs off toward MIN_DPI if that would exceed MAX_PIXELS. */
    private static float dpiFor(PDPage page) {
        float widthIn = page.getMediaBox().getWidth() / 72f;
        float heightIn = page.getMediaBox().getHeight() / 72f;
        if (widthIn <= 0 || heightIn <= 0) {
            return TARGET_DPI;
        }
        double maxDpiForPixelBudget = Math.sqrt(TesseractOcrEngine.MAX_PIXELS / (double) (widthIn * heightIn));
        return (float) Math.max(MIN_DPI, Math.min(TARGET_DPI, maxDpiForPixelBudget));
    }
}
