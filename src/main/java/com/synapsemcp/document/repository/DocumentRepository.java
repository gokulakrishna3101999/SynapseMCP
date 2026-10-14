package com.synapsemcp.document.repository;

import com.synapsemcp.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** Application-level tenant guard in addition to RLS (double-guard, per ADR-03). */
    Optional<Document> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Document> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
