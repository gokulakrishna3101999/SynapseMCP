package com.synapsemcp.ingestion.repository;

import com.synapsemcp.ingestion.entity.IngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {
}
