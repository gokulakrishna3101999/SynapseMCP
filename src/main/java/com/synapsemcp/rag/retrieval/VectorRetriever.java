package com.synapsemcp.rag.retrieval;

import com.synapsemcp.rag.repository.ChunkRepository;
import com.synapsemcp.rag.repository.ChunkRepository.ChunkMatchRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Top-K HNSW cosine similarity search over one tenant's chunks (design.md section 5.1). Filtering by
 * {@code tenant_id} alone is sufficient scoping since each tenant has exactly one knowledge base
 * (ADR-01); RLS is the defense-in-depth layer underneath this application-level filter (ADR-03).
 * <p>{@code TOP_K} is an internal tuning value, not a spec requirement - raised from 5 to 8 after a real
 * retrieval gap found live: fine-grained per-line/per-fact chunking (see ImageLineChunkingStrategy,
 * HeadingAwareChunkingStrategy's fact-sheet mode) deliberately keeps a title line and its detail line (or
 * two adjacent related facts) as separate chunks to avoid diluting either one's embedding - but that means
 * a query whose wording matches the title chunk better than the detail chunk can push the detail chunk
 * out of a too-small top-K entirely, even though together they clearly answer the question. A slightly
 * wider top-K gives the synthesis step a real chance to see both without the retrieval cutoff excluding
 * one outright.
 */
@Component
public class VectorRetriever {

    public static final int TOP_K = 8;

    private final ChunkRepository chunkRepository;

    public VectorRetriever(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    /**
     * Explicit transaction boundary: custom/native query repository methods (findNearest here) don't
     * get Spring Data's default per-call transaction, so without this the tenant never gets bound to
     * {@code app.current_tenant_id} and RLS silently returns zero rows.
     */
    @Transactional(readOnly = true)
    public List<ChunkMatch> search(UUID tenantId, float[] queryEmbedding) {
        String queryVector = VectorLiteral.of(queryEmbedding);
        return chunkRepository.findNearest(tenantId, queryVector, TOP_K).stream()
                .map(VectorRetriever::toChunkMatch)
                .toList();
    }

    private static ChunkMatch toChunkMatch(ChunkMatchRow row) {
        return new ChunkMatch(row.getChunkId(), row.getDocumentId(), row.getDocumentName(), row.getContent(),
                row.getSimilarity());
    }
}
