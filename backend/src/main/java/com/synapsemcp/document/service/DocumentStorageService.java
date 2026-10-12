package com.synapsemcp.document.service;

import com.synapsemcp.config.StorageProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.InputStream;
import java.util.UUID;

/**
 * Transient S3 staging. Objects live only while the async worker parses them and are deleted immediately
 * after the chunks and embeddings are committed (or on failure).
 */
@Service
public class DocumentStorageService {

    private static final int MAX_FILENAME_LENGTH = 200;

    private final S3Client s3Client;
    private final String bucket;

    public DocumentStorageService(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.bucket();
    }

    /**
     * Streams the file to {@code tenants/{tenantId}/staging/{docId}/{filename}} without buffering it in memory.
     *
     * @return the S3 key, to be stored in {@code documents.storage_path}
     */
    public String stageFile(UUID tenantId, UUID documentId, String filename, InputStream content,
                            long size, String contentType) {
        String key = stagingKey(tenantId, documentId, filename);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        return key;
    }

    /** Opens the staged object for the ingestion worker; the caller must close the stream. */
    public InputStream loadStagedFile(String storagePath) {
        return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(storagePath).build());
    }

    /** Permanently deletes the staged object. S3 deletes are idempotent, so a repeat call is harmless. */
    public void purgeStagedFile(String storagePath) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storagePath).build());
    }

    static String stagingKey(UUID tenantId, UUID documentId, String filename) {
        return "tenants/" + tenantId + "/staging/" + documentId + "/" + sanitize(filename);
    }

    /** Drops any path component and replaces characters that are awkward in S3 keys; the original name stays in the DB. */
    static String sanitize(String filename) {
        String name = filename == null ? "" : filename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(name.length() - MAX_FILENAME_LENGTH);
        }
        return name.isBlank() || name.chars().allMatch(c -> c == '.') ? "upload" : name;
    }
}
