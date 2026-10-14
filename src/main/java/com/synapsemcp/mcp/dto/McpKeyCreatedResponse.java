package com.synapsemcp.mcp.dto;

import java.time.Instant;
import java.util.UUID;

/** The plaintext key is returned exactly once, here, and never again (design.md section 7.4). */
public record McpKeyCreatedResponse(UUID id, String name, String key, Instant createdAt) {
}
