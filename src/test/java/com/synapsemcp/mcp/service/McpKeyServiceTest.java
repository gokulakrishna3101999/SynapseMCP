package com.synapsemcp.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.mcp.dto.McpKeyCreatedResponse;
import com.synapsemcp.mcp.entity.ApiKey;
import com.synapsemcp.mcp.repository.ApiKeyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class McpKeyServiceTest {

    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final McpKeyService service = new McpKeyService(apiKeyRepository, mock(AuditLogService.class));

    @Test
    void createGeneratesAPlaintextKeyNeverPersistedOnlyItsHash() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        McpKeyCreatedResponse response = service.create(tenantId, userId, "my key");

        assertThat(response.key()).startsWith(ApiKeyHasher.KEY_PREFIX);
        assertThat(response.name()).isEqualTo("my key");
        // The returned plaintext must never equal what gets persisted as the hash.
        assertThat(response.key()).isNotEqualTo(ApiKeyHasher.sha256Hex(response.key()));
    }

    @Test
    void blankNameFallsBackToDefault() {
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        McpKeyCreatedResponse response = service.create(UUID.randomUUID(), UUID.randomUUID(), "   ");

        assertThat(response.name()).isEqualTo("Default MCP Key");
    }

    @Test
    void persistedKeyHashMatchesTheReturnedPlaintext() {
        UUID tenantId = UUID.randomUUID();
        var savedKey = new ApiKey[1];
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> {
            savedKey[0] = inv.getArgument(0);
            return savedKey[0];
        });

        McpKeyCreatedResponse response = service.create(tenantId, UUID.randomUUID(), "k");

        assertThat(savedKey[0].getKeyHash()).isEqualTo(ApiKeyHasher.sha256Hex(response.key()));
        assertThat(savedKey[0].getKeyPrefix()).isEqualTo(response.key().substring(0, 16));
    }

    @Test
    void twoGeneratedKeysAreNeverTheSame() {
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        McpKeyCreatedResponse first = service.create(UUID.randomUUID(), UUID.randomUUID(), "a");
        McpKeyCreatedResponse second = service.create(UUID.randomUUID(), UUID.randomUUID(), "b");

        assertThat(first.key()).isNotEqualTo(second.key());
    }

    @Test
    void listMapsEntitiesToSummariesWithoutExposingTheHash() {
        UUID tenantId = UUID.randomUUID();
        ApiKey key = new ApiKey();
        key.setId(UUID.randomUUID());
        key.setName("n");
        key.setKeyPrefix("syn_mcp_abcd1234");
        key.setRevoked(false);
        when(apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(key));

        var summaries = service.list(tenantId);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).keyPrefix()).isEqualTo("syn_mcp_abcd1234");
        assertThat(summaries.get(0).revoked()).isFalse();
    }

    @Test
    void revokeSetsTheFlagWhenTheKeyBelongsToTheTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        ApiKey key = new ApiKey();
        key.setId(keyId);
        key.setKeyPrefix("syn_mcp_abcd1234");
        when(apiKeyRepository.findByIdAndTenantId(keyId, tenantId)).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.revoke(tenantId, keyId);

        assertThat(key.isRevoked()).isTrue();
    }

    @Test
    void revokeRejectsAKeyThatDoesNotBelongToTheTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(apiKeyRepository.findByIdAndTenantId(keyId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(tenantId, keyId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
