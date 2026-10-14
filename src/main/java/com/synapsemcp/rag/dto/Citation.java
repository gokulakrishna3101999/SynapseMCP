package com.synapsemcp.rag.dto;

import java.util.UUID;

/** One LOCAL_KB citation, per design.md section 7.3. */
public record Citation(UUID documentId, String documentName, UUID chunkId, String snippet, double similarityScore) {
}
