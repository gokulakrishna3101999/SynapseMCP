package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * OCR for .png, .jpg, .jpeg and .webp images over 2 MiB, using Tess4J (Tesseract native libraries bundled in the jar)
 * with the English language data bundled in {@code resources/tessdata}. The image is decoded with ImageIO first,
 * which gives one code path for every format (WebP via the TwelveMonkeys reader). The OCR itself (band-splitting
 * for mixed-contrast images, dual-pass normal/inverted) lives in {@link TesseractOcrEngine}, shared with
 * {@link PdfBoxDocumentParser}'s scanned-PDF-page OCR.
 */
@Component
public class TesseractOcrDocumentParser implements DocumentParserStrategy {

    private final TesseractOcrEngine engine;

    public TesseractOcrDocumentParser(TesseractOcrEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.IMAGE;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        BufferedImage image = decode(request);
        return new ExtractionResult(engine.ocr(image, request.filename()));
    }

    private BufferedImage decode(ExtractionRequest request) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(request.content()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new DocumentExtractionException("Unsupported or corrupt image: " + request.filename());
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > TesseractOcrEngine.MAX_PIXELS) {
                    throw new DocumentExtractionException("Image is too large to OCR (" + pixels + " pixels): "
                            + request.filename());
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof DocumentExtractionException dee) {
                throw dee;
            }
            throw new DocumentExtractionException("Could not decode image " + request.filename() + ": "
                    + ex.getMessage(), ex);
        }
    }
}
