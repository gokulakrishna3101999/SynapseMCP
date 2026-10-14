package com.synapsemcp.ingestion.entity;

public enum JobStage {
    QUEUED, EXTRACTION, SPLITTING, VECTORIZING, PURGING_S3, COMPLETED, FAILED
}
