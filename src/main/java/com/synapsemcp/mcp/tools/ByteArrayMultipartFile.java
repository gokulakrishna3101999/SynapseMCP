package com.synapsemcp.mcp.tools;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/**
 * Adapts raw bytes (decoded from an MCP tool call's Base64 argument) to the {@link MultipartFile} contract
 * that {@code DocumentService.upload} expects, so an MCP upload reuses the exact same validation/staging/
 * ingestion pipeline as the REST upload endpoint instead of a second, parallel implementation.
 */
final class ByteArrayMultipartFile implements MultipartFile {

    private final String filename;
    private final byte[] content;

    ByteArrayMultipartFile(String filename, byte[] content) {
        this.filename = filename;
        this.content = content;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return filename;
    }

    @Override
    public String getContentType() {
        // DocumentService resolves the content type from the filename extension (FileCategory.resolve),
        // never from this - there is no real multipart Content-Type header to report here.
        return null;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(content);
        }
    }
}
