package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Component;

/**
 * OCR for .png, .jpg, .jpeg and .webp images over 2 MiB, using Tess4J (Tesseract native libraries bundled in the jar)
 * with the English language data bundled in {@code resources/tessdata}. The image is decoded with ImageIO first,
 * which gives one code path for every format (WebP via the TwelveMonkeys reader).
 */
@Component
public class TesseractOcrDocumentParser implements DocumentParserStrategy {

    private static final String TESSDATA_RESOURCE = "/tessdata/eng.traineddata";
    /** Rejects decompression bombs: a tiny file that declares an enormous canvas would exhaust the heap. */
    static final long MAX_PIXELS = 60_000_000L;

    private volatile Path dataDirectory;

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.IMAGE;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        BufferedImage image = decode(request);
        try {
            Tesseract tesseract = new Tesseract(); // not thread-safe, so one instance per call
            tesseract.setDatapath(dataDirectory().toString());
            tesseract.setLanguage("eng");
            String text = tesseract.doOCR(image);
            return new ExtractionResult(MarkdownSupport.normalize(text));
        } catch (TesseractException | IOException | LinkageError ex) {
            throw new DocumentExtractionException("OCR failed for " + request.filename() + ": " + ex.getMessage(), ex);
        }
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
                if (pixels > MAX_PIXELS) {
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

    /** Tesseract needs the language data on disk, so it is copied out of the jar once into a temp directory. */
    private Path dataDirectory() throws IOException {
        Path dir = dataDirectory;
        if (dir == null) {
            synchronized (this) {
                dir = dataDirectory;
                if (dir == null) {
                    dir = Files.createTempDirectory("synapse-tessdata");
                    Path file = dir.resolve("eng.traineddata");
                    try (InputStream data = TesseractOcrDocumentParser.class.getResourceAsStream(TESSDATA_RESOURCE)) {
                        if (data == null) {
                            throw new IOException("Bundled OCR data missing from the application: " + TESSDATA_RESOURCE);
                        }
                        Files.copy(data, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    file.toFile().deleteOnExit();
                    dir.toFile().deleteOnExit();
                    dataDirectory = dir;
                }
            }
        }
        return dir;
    }
}
