package com.synapsemcp.mcp.dto;

import java.time.Instant;
import java.util.UUID;

/** GET /api/v1/keys/mcp row: prefix, creation date, last used timestamp - never the plaintext or hash. */
public record McpKeySummary(UUID id, String name, String keyPrefix, boolean revoked, Instant createdAt,
                             Instant lastUsedAt) {
}
