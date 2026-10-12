package com.synapsemcp.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.config.S3Config;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Stages, reads back and purges a real object in the configured bucket, using the app's own S3 configuration.
 * Skipped unless AWS_ACCESS_KEY is set. Run with: ./mvnw test -Dtest=S3LiveIT
 */
@EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY", matches = ".+")
@SpringBootTest(classes = {S3Config.class, DocumentStorageService.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S3LiveIT {

    @Autowired
    DocumentStorageService storage;

    @Test
    void stageLoadAndPurgeRoundTrip() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        byte[] payload = "synapse s3 round trip".getBytes(StandardCharsets.UTF_8);

        String key = storage.stageFile(tenantId, documentId, "live check.txt",
                new ByteArrayInputStream(payload), payload.length, "text/plain");
        try {
            assertThat(key).startsWith("tenants/" + tenantId + "/staging/" + documentId + "/");
            try (var in = storage.loadStagedFile(key)) {
                assertThat(in.readAllBytes()).isEqualTo(payload);
            }
        } finally {
            storage.purgeStagedFile(key);
        }

        assertThatThrownBy(() -> storage.loadStagedFile(key).close()).isInstanceOf(NoSuchKeyException.class);
    }
}
