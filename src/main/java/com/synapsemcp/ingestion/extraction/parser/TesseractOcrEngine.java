package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared Tesseract OCR core, used both by {@link TesseractOcrDocumentParser} (standalone images) and
 * {@link PdfBoxDocumentParser} (scanned PDF pages, rendered to an image first) - the same OCR quality
 * problem and fix applies to both: Tesseract binarizes against one global brightness threshold per call,
 * so a single pass over an image with distinct background-color bands (e.g. a white section with black
 * text next to a colored banner with light text) structurally cannot read every band correctly. This
 * splits the image into brightness bands first, OCR's each one (normal and color-inverted, since a
 * band's own polarity isn't known in advance), and merges the results by line.
 */
@Component
public class TesseractOcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);
    private static final String TESSDATA_RESOURCE = "/tessdata/eng.traineddata";
    /** Rejects decompression bombs: a tiny file that declares an enormous canvas would exhaust the heap. */
    public static final long MAX_PIXELS = 60_000_000L;
    /** A background-brightness swing bigger than this (0-255) starts a new band. */
    private static final int BAND_BRIGHTNESS_TOLERANCE = 35;
    /** Bands thinner than this are noise (an antialiased edge between two real bands) - merged into the previous one. */
    private static final int MIN_BAND_HEIGHT_PX = 20;
    /**
     * A real-world scan (letterhead gradients, logos, watermarks) can in principle trigger far more
     * brightness swings than a clean infographic with a handful of solid-color blocks. This caps that
     * cost: above this many detected bands, fall back to one dual-pass on the whole image instead,
     * bounding cost at exactly 2 Tesseract calls regardless of how noisy the source is. (A real multi-page
     * scanned PDF was found live to intermittently time out; live diagnostic logging during a repro traced
     * that specifically to host CPU contention from concurrent heavy OCR runs - typically only 1-2 bands
     * per real page, well under this cap - not to band count. This cap is still kept as a legitimate,
     * independent safety net against the band-explosion scenario it targets, which just wasn't what
     * happened that time.)
     */
    private static final int MAX_BANDS = 12;

    private volatile Path dataDirectory;

    /** OCRs one already-decoded image and returns normalized text (never null; may be blank if nothing was read). */
    public String ocr(BufferedImage image, String sourceDescription) {
        try {
            Tesseract tesseract = new Tesseract(); // not thread-safe, so one instance per call (every pass, same thread)
            tesseract.setDatapath(dataDirectory().toString());
            tesseract.setLanguage("eng");

            List<BufferedImage> bands = splitIntoBackgroundBands(image);
            Set<String> lines = new LinkedHashSet<>();
            for (BufferedImage band : bands) {
                addLines(lines, runOcr(tesseract, band, sourceDescription));
                addLines(lines, tryOcr(tesseract, invert(band), sourceDescription));
            }
            return MarkdownSupport.normalize(String.join("\n", lines));
        } catch (IOException ex) {
            throw new DocumentExtractionException("OCR failed for " + sourceDescription + ": " + ex.getMessage(), ex);
        }
    }

    private static String runOcr(Tesseract tesseract, BufferedImage image, String sourceDescription) {
        try {
            return tesseract.doOCR(image);
        } catch (TesseractException | LinkageError ex) {
            throw new DocumentExtractionException("OCR failed for " + sourceDescription + ": " + ex.getMessage(), ex);
        }
    }

    /** The inverted pass is a best-effort second chance, not required: if it fails, keep whatever else was found. */
    private static String tryOcr(Tesseract tesseract, BufferedImage image, String sourceDescription) {
        try {
            return tesseract.doOCR(image);
        } catch (TesseractException | LinkageError ex) {
            log.debug("Inverted-pass OCR failed for {}, continuing without it", sourceDescription, ex);
            return "";
        }
    }

    private static void addLines(Set<String> lines, String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
    }

    /**
     * Groups rows into bands by a smoothed background-brightness signal. Uses each row's 90th-percentile
     * pixel brightness (not the mean) as the "background" estimate, since in a normal text row the large
     * majority of pixels are background and only a minority are ink - the 90th percentile tracks the
     * background color even through rows dense with text, while the mean would dip with every text row
     * and falsely fragment a single real band into dozens of tiny ones.
     */
    private static List<BufferedImage> splitIntoBackgroundBands(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rowBrightness = new int[height];
        for (int y = 0; y < height; y++) {
            int[] values = new int[width];
            for (int x = 0; x < width; x++) {
                values[x] = luminosity(image.getRGB(x, y));
            }
            java.util.Arrays.sort(values);
            rowBrightness[y] = values[(int) (width * 0.9)];
        }

        List<int[]> ranges = new ArrayList<>(); // [startY, endY)
        int bandStart = 0;
        long runningSum = rowBrightness[0];
        int runningCount = 1;
        for (int y = 1; y < height; y++) {
            int bandAverage = (int) (runningSum / runningCount);
            if (Math.abs(rowBrightness[y] - bandAverage) > BAND_BRIGHTNESS_TOLERANCE) {
                ranges.add(new int[] {bandStart, y});
                bandStart = y;
                runningSum = 0;
                runningCount = 0;
            }
            runningSum += rowBrightness[y];
            runningCount++;
        }
        ranges.add(new int[] {bandStart, height});

        // Fold any sliver (an antialiased transition row or two) into the band before it, rather than
        // dropping it or OCR-ing a near-empty strip.
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[1] - range[0] < MIN_BAND_HEIGHT_PX) {
                merged.get(merged.size() - 1)[1] = range[1];
            } else {
                merged.add(range);
            }
        }

        if (merged.size() > MAX_BANDS) {
            log.debug("Image produced {} bands (over the {} cap) - likely a noisy real-world scan, not a clean "
                    + "color-blocked layout; OCR-ing the whole image in one pass instead to bound cost", merged.size(), MAX_BANDS);
            return List.of(image);
        }

        List<BufferedImage> bands = new ArrayList<>(merged.size());
        for (int[] range : merged) {
            bands.add(image.getSubimage(0, range[0], width, range[1] - range[0]));
        }
        return bands;
    }

    private static int luminosity(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    /** Per-channel RGB inversion; flips which polarity (dark-on-light vs light-on-dark) OCR's binarization favors. */
    private static BufferedImage invert(BufferedImage src) {
        BufferedImage inverted = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int r = 255 - ((rgb >> 16) & 0xFF);
                int g = 255 - ((rgb >> 8) & 0xFF);
                int b = 255 - (rgb & 0xFF);
                inverted.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return inverted;
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
                    try (InputStream data = TesseractOcrEngine.class.getResourceAsStream(TESSDATA_RESOURCE)) {
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
