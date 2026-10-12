package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Text extraction from PDFs with their embedded text layer, in reading order. Pages are separated by a blank line.
 * A scanned PDF has no text layer; there is no OCR for PDFs in Tier 2, so it fails with a clear message.
 */
@Component
public class PdfBoxDocumentParser implements DocumentParserStrategy {

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
            StringBuilder out = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = MarkdownSupport.normalize(stripper.getText(document));
                if (!text.isEmpty()) {
                    out.append(text).append("\n\n");
                }
            }
            String markdown = MarkdownSupport.normalize(out.toString());
            if (markdown.isEmpty()) {
                throw new DocumentExtractionException("This PDF has no extractable text (it may be a scan; OCR is not "
                        + "available for PDFs over 2 MiB): " + request.filename());
            }
            return new ExtractionResult(markdown);
        } catch (InvalidPasswordException ex) {
            throw new DocumentExtractionException("The PDF is password-protected: " + request.filename(), ex);
        } catch (IOException ex) {
            throw new DocumentExtractionException("Could not read PDF " + request.filename() + ": " + ex.getMessage(), ex);
        }
    }
}
