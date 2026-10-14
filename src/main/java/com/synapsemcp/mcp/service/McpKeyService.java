package com.synapsemcp.mcp.service;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.mcp.dto.McpKeyCreatedResponse;
import com.synapsemcp.mcp.dto.McpKeySummary;
import com.synapsemcp.mcp.entity.ApiKey;
import com.synapsemcp.mcp.repository.ApiKeyRepository;
import com.synapsemcp.security.CurrentUser;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues and revokes {@code syn_mcp_...} API keys (design.md section 7.4, architecture.md ADR-04). */
@Service
public class McpKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_RANDOM_BYTES = 24;
    private static final int KEY_PREFIX_DISPLAY_LENGTH = 16;

    private final ApiKeyRepository apiKeyRepository;
    private final AuditLogService auditLogService;

    public McpKeyService(ApiKeyRepository apiKeyRepository, AuditLogService auditLogService) {
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogService = auditLogService;
    }

    /** The plaintext key is never persisted and only ever returned from this one call. */
    @Transactional
    public McpKeyCreatedResponse create(UUID tenantId, UUID userId, String requestedName) {
        String plaintext = generatePlaintextKey();
        ApiKey key = new ApiKey();
        key.setTenantId(tenantId);
        key.setUserId(userId);
        key.setName(requestedName == null || requestedName.isBlank() ? "Default MCP Key" : requestedName);
        key.setKeyPrefix(plaintext.substring(0, KEY_PREFIX_DISPLAY_LENGTH));
        key.setKeyHash(ApiKeyHasher.sha256Hex(plaintext));
        key = apiKeyRepository.save(key);
        auditLogService.record(tenantId, userId, "MCP_KEY_CREATE", "API_KEY", key.getId(),
                Map.of("keyPrefix", key.getKeyPrefix()));
        return new McpKeyCreatedResponse(key.getId(), key.getName(), plaintext, key.getCreatedAt());
    }

    /**
     * Explicit transaction boundary: the custom derived queries below don't get Spring Data's default
     * per-call transaction, so without this the tenant never gets bound to {@code app.current_tenant_id}
     * and RLS silently returns zero rows.
     */
    @Transactional(readOnly = true)
    public List<McpKeySummary> list(UUID tenantId) {
        return apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(k -> new McpKeySummary(k.getId(), k.getName(), k.getKeyPrefix(), k.isRevoked(), k.getCreatedAt(),
                        k.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public void revoke(UUID tenantId, UUID keyId) {
        ApiKey key = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_KEY_NOT_FOUND", "No MCP key " + keyId));
        key.setRevoked(true);
        apiKeyRepository.save(key);
        auditLogService.record(tenantId, CurrentUser.userId(), "MCP_KEY_REVOKE", "API_KEY", key.getId(),
                Map.of("keyPrefix", key.getKeyPrefix()));
    }

    private static String generatePlaintextKey() {
        byte[] bytes = new byte[KEY_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return ApiKeyHasher.KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }
}
