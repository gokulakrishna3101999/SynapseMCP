package com.synapsemcp.ingestion.extraction;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Generates real PDFs and images for live extraction tests (no extra libraries needed for PDF/PNG/JPEG). */
final class TestDocuments {

    private TestDocuments() {
    }

    /** A PDF page: either a text/graphics content stream, or a full-page scanned JPEG (no text layer). */
    record Page(String content, byte[] fullPageJpeg, int jpegWidth, int jpegHeight) {
        static Page text(String content) {
            return new Page(content, null, 0, 0);
        }

        static Page scan(byte[] jpeg, int width, int height) {
            return new Page("q 612 0 0 792 0 0 cm /Im0 Do Q", jpeg, width, height);
        }
    }

    static String textLines(int fontSize, int startY, List<String> lines) {
        StringBuilder sb = new StringBuilder("BT /F1 " + fontSize + " Tf 56 " + startY + " Td " + (fontSize + 4) + " TL\n");
        for (String line : lines) {
            sb.append('(').append(escape(line)).append(") Tj T*\n");
        }
        return sb.append("ET").toString();
    }

    /** A ruled 3-column table drawn with lines plus positioned text, like a real report would contain. */
    static String tablePage(String title, String[][] rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(textLines(18, 740, List.of(title)));
        int top = 700;
        int rowHeight = 28;
        int[] colX = {56, 256, 356, 456};
        for (int r = 0; r <= rows.length; r++) {
            int y = top - r * rowHeight;
            sb.append("\n").append(colX[0]).append(' ').append(y).append(" m ").append(colX[3]).append(' ')
                    .append(y).append(" l S");
        }
        for (int x : colX) {
            sb.append("\n").append(x).append(' ').append(top).append(" m ").append(x).append(' ')
                    .append(top - rows.length * rowHeight).append(" l S");
        }
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 3; c++) {
                sb.append("\nBT /F1 12 Tf ").append(colX[c] + 6).append(' ').append(top - r * rowHeight - 19)
                        .append(" Td (").append(escape(rows[r][c])).append(") Tj ET");
            }
        }
        return sb.toString();
    }

    static byte[] pdf(List<Page> pages) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");

        // ids: 1 catalog, 2 pages tree, 3 font, then per page: page, content, [image]
        List<Integer> pageIds = new ArrayList<>();
        int nextId = 4;
        List<int[]> ids = new ArrayList<>(); // {pageId, contentId, imageId or 0}
        for (Page page : pages) {
            int pageId = nextId++;
            int contentId = nextId++;
            int imageId = page.fullPageJpeg() != null ? nextId++ : 0;
            ids.add(new int[] {pageId, contentId, imageId});
            pageIds.add(pageId);
        }
        int total = nextId - 1;

        List<String> kids = new ArrayList<>();
        for (int id : pageIds) {
            kids.add(id + " 0 R");
        }
        object(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>");
        object(out, offsets, "<< /Type /Pages /Kids [" + String.join(" ", kids) + "] /Count " + pages.size() + " >>");
        object(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            int[] id = ids.get(i);
            String resources = "/Resources << /Font << /F1 3 0 R >>"
                    + (id[2] != 0 ? " /XObject << /Im0 " + id[2] + " 0 R >>" : "") + " >>";
            object(out, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " + id[1]
                    + " 0 R " + resources + " >>");
            byte[] content = page.content().getBytes(StandardCharsets.ISO_8859_1);
            offsets.add(out.size());
            write(out, (id[1]) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            out.writeBytes(content);
            write(out, "\nendstream\nendobj\n");
            if (id[2] != 0) {
                offsets.add(out.size());
                write(out, id[2] + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + page.jpegWidth()
                        + " /Height " + page.jpegHeight() + " /ColorSpace /DeviceRGB /BitsPerComponent 8 "
                        + "/Filter /DCTDecode /Length " + page.fullPageJpeg().length + " >>\nstream\n");
                out.writeBytes(page.fullPageJpeg());
                write(out, "\nendstream\nendobj\n");
            }
        }
        int xref = out.size();
        write(out, "xref\n0 " + (total + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            write(out, String.format("%010d 00000 n \n", offset));
        }
        write(out, "trailer\n<< /Size " + (total + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    static BufferedImage textImage(int width, int height, int fontSize, List<String> lines) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        int y = fontSize + 30;
        for (String line : lines) {
            g.drawString(line, 40, y);
            y += fontSize + 24;
        }
        g.dispose();
        return image;
    }

    static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IOException("No ImageIO writer for " + format);
        }
        return out.toByteArray();
    }

    private static void object(ByteArrayOutputStream out, List<Integer> offsets, String body) {
        offsets.add(out.size());
        write(out, offsets.size() + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
