package com.synapsemcp.mcp.service;

import com.synapsemcp.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a plaintext MCP key to its tenant via the {@code authenticate_api_key} SECURITY DEFINER function
 * (needed because RLS on {@code api_keys} hides every row until a tenant is known).
 */
@Service
public class ApiKeyAuthenticator {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyAuthenticator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthenticatedPrincipal> authenticate(String plaintextKey) {
        if (plaintextKey == null || !plaintextKey.startsWith(ApiKeyHasher.KEY_PREFIX)) {
            return Optional.empty();
        }
        String hash = ApiKeyHasher.sha256Hex(plaintextKey);
        return jdbcTemplate.query(
                        "SELECT out_tenant_id, out_user_id FROM authenticate_api_key(?)",
                        (rs, i) -> new AuthenticatedPrincipal(
                                rs.getObject("out_user_id", UUID.class),
                                rs.getObject("out_tenant_id", UUID.class),
                                null,
                                "MCP"),
                        hash)
                .stream().findFirst();
    }
}
