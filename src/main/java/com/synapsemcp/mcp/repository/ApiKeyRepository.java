package com.synapsemcp.mcp.repository;

import com.synapsemcp.mcp.entity.ApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Application-level tenant guard in addition to RLS (double-guard, per ADR-03). */
    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);
}
