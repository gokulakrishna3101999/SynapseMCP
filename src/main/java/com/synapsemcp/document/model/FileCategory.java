package com.synapsemcp.document.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The 7 supported categories and their extensions (instruction.md section 4.1), with canonical MIME types. */
public enum FileCategory {
    PDF(Map.of("pdf", "application/pdf")),
    WORD(Map.of(
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    EXCEL(Map.of(
            "xls", "application/vnd.ms-excel",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    POWERPOINT(Map.of(
            "ppt", "application/vnd.ms-powerpoint",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    HTML(Map.of("html", "text/html", "htm", "text/html")),
    TEXT(Map.of(
            "txt", "text/plain",
            "csv", "text/csv",
            "md", "text/markdown",
            "json", "application/json")),
    IMAGE(Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp"));

    private final Map<String, String> mimeByExtension;

    FileCategory(Map<String, String> mimeByExtension) {
        this.mimeByExtension = mimeByExtension;
    }

    /** The MIME type is derived from the extension, never trusted from the client-supplied header. */
    public record Resolved(FileCategory category, String contentType) {
    }

    public static Optional<Resolved> resolve(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return Optional.empty();
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (FileCategory category : values()) {
            String mime = category.mimeByExtension.get(ext);
            if (mime != null) {
                return Optional.of(new Resolved(category, mime));
            }
        }
        return Optional.empty();
    }
}
