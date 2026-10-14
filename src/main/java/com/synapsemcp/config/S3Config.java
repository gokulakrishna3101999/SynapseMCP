package com.synapsemcp.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class S3Config {

    /**
     * Credentials come explicitly from AWS_ACCESS_KEY / AWS_SECRET_KEY, not the SDK default chain, so unrelated
     * machine-level AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY values can never be picked up by accident.
     * The endpoint is pinned for the same reason: the SDK would otherwise honor an ambient AWS_ENDPOINT_URL
     * (e.g. a LocalStack address) and silently send staged documents there. It is real AWS unless
     * S3_ENDPOINT is set explicitly, in which case path-style addressing is used (LocalStack/MinIO).
     */
    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        if (properties.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint())).forcePathStyle(true);
        } else {
            builder.endpointOverride(URI.create("https://s3." + properties.region() + ".amazonaws.com"));
        }
        return builder.build();
    }
}
