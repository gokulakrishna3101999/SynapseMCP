package com.synapsemcp.mcp.dto;

/** POST /api/v1/keys/mcp request body; {@code name} is optional (defaults to "Default MCP Key"). */
public record McpKeyCreateRequest(String name) {
}
