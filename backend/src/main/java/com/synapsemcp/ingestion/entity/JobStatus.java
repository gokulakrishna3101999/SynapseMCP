package com.synapsemcp.ingestion.entity;

public enum JobStatus {
    QUEUED, PARSING, CHUNKING, EMBEDDING, PURGING_S3, COMPLETED, FAILED
}
