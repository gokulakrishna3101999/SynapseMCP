package com.synapsemcp.rag.retrieval;

import java.util.UUID;

/** One retrieved chunk and its cosine similarity (1 - pgvector's {@code <=>} distance) to the query. */
public record ChunkMatch(UUID chunkId, UUID documentId, String documentName, String content, double similarityScore) {
}
