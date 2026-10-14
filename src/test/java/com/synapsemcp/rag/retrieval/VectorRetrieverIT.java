package com.synapsemcp.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.auth.entity.Tenant;
import com.synapsemcp.auth.repository.TenantRepository;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.rag.entity.Chunk;
import com.synapsemcp.rag.repository.ChunkRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real Postgres + pgvector HNSW search: proves tenant isolation (a near-identical vector belonging to a
 * different tenant must never be returned) and correct cosine-similarity ordering, not just that the
 * query runs. Run with: ./mvnw test -Dtest=VectorRetrieverIT
 */
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@SpringBootTest
class VectorRetrieverIT {

    @Autowired
    TenantRepository tenantRepository;
    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    ChunkRepository chunkRepository;
    @Autowired
    VectorRetriever retriever;

    private UUID tenantAId;
    private UUID tenantBId;

    @AfterEach
    void tearDown() {
        if (tenantAId != null) {
            tenantRepository.deleteById(tenantAId);
        }
        if (tenantBId != null) {
            tenantRepository.deleteById(tenantBId);
        }
    }

    @Test
    void returnsOnlyTheRequestingTenantsChunksOrderedByBestMatchFirst() {
        float[] queryEmbedding = pattern(i -> Math.sin(i));
        float[] exactMatch = queryEmbedding.clone();
        float[] differentPattern = pattern(i -> Math.cos(i));
        float[] constantPattern = pattern(i -> 1.0);

        UUID tenantA = newTenantWithKb("vector-retriever-it-a");
        tenantAId = tenantA;
        UUID docA = newDocument(tenantA, knowledgeBaseIdFor(tenantA));
        saveChunk(tenantA, docA, 0, "exact match for tenant A", exactMatch);
        saveChunk(tenantA, docA, 1, "different pattern for tenant A", differentPattern);
        saveChunk(tenantA, docA, 2, "constant pattern for tenant A", constantPattern);

        // Tenant B has a chunk with the SAME embedding as the query - it must never leak into A's results,
        // even though it would otherwise be the best possible match.
        UUID tenantB = newTenantWithKb("vector-retriever-it-b");
        tenantBId = tenantB;
        UUID docB = newDocument(tenantB, knowledgeBaseIdFor(tenantB));
        saveChunk(tenantB, docB, 0, "exact match for tenant B - must not leak", exactMatch);

        List<ChunkMatch> results = retriever.search(tenantA, queryEmbedding);

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(m -> assertThat(m.content()).contains("tenant A"));
        assertThat(results).noneMatch(m -> m.content().contains("tenant B"));

        // Best match first (ascending distance == descending similarity), and the exact match is ~1.0.
        assertThat(results.get(0).content()).isEqualTo("exact match for tenant A");
        assertThat(results.get(0).similarityScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(results.get(0).similarityScore()).isGreaterThan(results.get(1).similarityScore());
        assertThat(results.get(1).similarityScore()).isGreaterThan(results.get(2).similarityScore());
    }

    @Test
    void emptyKnowledgeBaseReturnsNoMatches() {
        UUID tenantA = newTenantWithKb("vector-retriever-it-empty");
        tenantAId = tenantA;

        List<ChunkMatch> results = retriever.search(tenantA, pattern(i -> Math.sin(i)));

        assertThat(results).isEmpty();
    }

    private UUID newTenantWithKb(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant = tenantRepository.save(tenant);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenant.getId());
        knowledgeBaseRepository.save(kb);
        return tenant.getId();
    }

    private UUID knowledgeBaseIdFor(UUID tenantId) {
        return knowledgeBaseRepository.findByTenantId(tenantId).orElseThrow().getId();
    }

    private UUID newDocument(UUID tenantId, UUID knowledgeBaseId) {
        Document document = new Document();
        document.setTenantId(tenantId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setName("fixture.txt");
        document.setContentType("text/plain");
        document.setFileSize(1);
        document.setIngestionTier(IngestionTier.TIER_2_JAVA_PARSER);
        document.setStatus(DocumentStatus.INDEXED);
        return documentRepository.save(document).getId();
    }

    private void saveChunk(UUID tenantId, UUID documentId, int index, String content, float[] embedding) {
        Chunk chunk = new Chunk();
        chunk.setTenantId(tenantId);
        chunk.setKnowledgeBaseId(knowledgeBaseIdFor(tenantId));
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setEmbedding(embedding);
        chunkRepository.save(chunk);
    }

    private static float[] pattern(java.util.function.DoubleUnaryOperator fn) {
        float[] v = new float[Chunk.EMBEDDING_DIMENSIONS];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) fn.applyAsDouble(i);
        }
        return v;
    }
}
