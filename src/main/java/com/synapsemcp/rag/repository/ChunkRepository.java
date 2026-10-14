package com.synapsemcp.rag.repository;

import com.synapsemcp.rag.entity.Chunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    long countByDocumentId(UUID documentId);

    /**
     * Top-{@code limit} chunks by HNSW cosine distance (design.md section 5.1: {@code ORDER BY embedding
     * <=> :queryVector ASC LIMIT 5}), scoped to one tenant. {@code queryVector} is a pgvector text literal
     * (e.g. {@code "[0.1,0.2,...]"}, see {@code VectorLiteral}); pgvector's {@code <=>} is cosine
     * *distance*, so similarity is computed here as {@code 1 - distance}. The document name join is for
     * citations (design.md section 7.1's {@code documentName} field).
     */
    @Query(value = "SELECT c.id AS chunkId, c.document_id AS documentId, c.content AS content, "
            + "d.name AS documentName, (1 - (c.embedding <=> CAST(:queryVector AS vector))) AS similarity "
            + "FROM chunks c JOIN documents d ON d.id = c.document_id WHERE c.tenant_id = :tenantId "
            + "ORDER BY c.embedding <=> CAST(:queryVector AS vector) ASC LIMIT :limit", nativeQuery = true)
    List<ChunkMatchRow> findNearest(@Param("tenantId") UUID tenantId, @Param("queryVector") String queryVector,
                                     @Param("limit") int limit);

    /** Native-query projection; getter names must match the query's column aliases (camelCase). */
    interface ChunkMatchRow {
        UUID getChunkId();

        UUID getDocumentId();

        String getContent();

        String getDocumentName();

        double getSimilarity();
    }
}
