package com.synapsemcp.document.repository;

import com.synapsemcp.document.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    /** The tenant's single unified KB. */
    Optional<KnowledgeBase> findByTenantId(UUID tenantId);
}
