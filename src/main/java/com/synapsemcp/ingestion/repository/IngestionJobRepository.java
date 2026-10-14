package com.synapsemcp.ingestion.repository;

import com.synapsemcp.ingestion.entity.IngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    /** Latest job for a document; a document has exactly one job in the current design. */
    Optional<IngestionJob> findFirstByDocumentIdOrderByCreatedAtDesc(UUID documentId);
}
