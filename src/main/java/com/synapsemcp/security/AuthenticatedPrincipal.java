package com.synapsemcp.security;

import java.util.UUID;

/** Identity placed in the SecurityContext after JWT or MCP API key authentication. {@code email} is null for MCP keys. */
public record AuthenticatedPrincipal(UUID userId, UUID tenantId, String email, String role) {
}
