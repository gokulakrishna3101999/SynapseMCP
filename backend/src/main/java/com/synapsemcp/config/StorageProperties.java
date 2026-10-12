package com.synapsemcp.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code endpoint} is optional: blank means real AWS S3 in {@code region}; set it only for LocalStack/MinIO. */
@Validated
@ConfigurationProperties(prefix = "synapse.storage.s3")
public record StorageProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        String endpoint) {

    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }

    /** Never print the credentials. */
    @Override
    public String toString() {
        return "StorageProperties[bucket=" + bucket + ", region=" + region + ", endpoint=" + endpoint + "]";
    }
}
