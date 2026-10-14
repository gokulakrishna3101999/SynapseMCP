package com.synapsemcp.ingestion.chunking;

/** One chunk of extracted Markdown, ready to be embedded. */
public record ChunkPayload(int chunkIndex, String content) {
}
