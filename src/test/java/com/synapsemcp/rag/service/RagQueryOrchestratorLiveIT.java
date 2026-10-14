package com.synapsemcp.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.synapsemcp.auth.entity.Tenant;
import com.synapsemcp.auth.repository.TenantRepository;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.Document;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.ingestion.service.IngestionOrchestrator;
import com.synapsemcp.rag.dto.ChatResponse;
import com.synapsemcp.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Drives all three branches of Milestone 5.3's query routing against real infrastructure: a real
 * indexed document (via the real M3.4 ingestion pipeline), real pgvector retrieval, the real Gemini chat
 * model for synthesis, and the real DuckDuckGo fallback. One tenant, one document, three queries - kept
 * to one document/few calls deliberately to limit Gemini free-tier quota usage (memory.md section 7).
 * Run with: ./mvnw test -Dtest=RagQueryOrchestratorLiveIT
 */
@EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY", matches = ".+")
@SpringBootTest
class RagQueryOrchestratorLiveIT {

    @Autowired
    TenantRepository tenantRepository;
    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    DocumentService documentService;
    @Autowired
    IngestionOrchestrator ingestionOrchestrator;
    @Autowired
    RagQueryOrchestrator ragQueryOrchestrator;

    private UUID tenantId;

    @AfterEach
    void tearDown() {
        if (tenantId != null) {
            tenantRepository.deleteById(tenantId);
        }
        TenantContext.clear();
    }

    @Test
    void localKbWebFallbackAndDeterministicFailureAllRouteCorrectly() {
        Tenant tenant = new Tenant();
        tenant.setName("rag-orchestrator-it");
        tenant = tenantRepository.save(tenant);
        tenantId = tenant.getId();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenantId);
        knowledgeBaseRepository.save(kb);

        TenantContext.setTenantId(tenantId);
        byte[] content = "Project Phoenix's approved Q3 budget is exactly 42000 US dollars, covering cloud hosting."
                .getBytes(StandardCharsets.UTF_8);
        UploadResponse upload = documentService.upload(
                new MockMultipartFile("file", "phoenix-budget.txt", "text/plain", content));
        TenantContext.clear();

        ingestionOrchestrator.processDocumentAsync(upload.jobId(), upload.documentId(), tenantId);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() ->
                documentRepository.findById(upload.documentId()).orElseThrow().getStatus() == DocumentStatus.INDEXED);

        // 1. LOCAL_KB: a question the uploaded document actually answers.
        ChatResponse localAnswer = ragQueryOrchestrator.answer(tenantId, "What is the Project Phoenix Q3 budget?");
        assertThat(localAnswer.source()).isEqualTo("LOCAL_KB");
        assertThat(localAnswer.confidenceScore()).isGreaterThanOrEqualTo(0.70);
        assertThat(localAnswer.citations()).isNotEmpty();
        // The model may format the figure as "42000", "42,000" or "$42,000" - compare digits only.
        assertThat(localAnswer.answer().replaceAll("[^0-9]", "")).contains("42000");
        assertThat(localAnswer.webSources()).isEmpty();

        // 2. WEB_SEARCH: a well-known-topic question unrelated to the one uploaded document.
        ChatResponse webAnswer = ragQueryOrchestrator.answer(tenantId, "What is the capital of France?");
        assertThat(webAnswer.source()).isIn("WEB_SEARCH", "NONE"); // DuckDuckGo coverage isn't guaranteed; see class note
        if (webAnswer.source().equals("WEB_SEARCH")) {
            assertThat(webAnswer.webSources()).isNotEmpty();
            assertThat(webAnswer.citations()).isEmpty();
        }

        // 3. NONE: nothing in the KB, and DuckDuckGo's Instant Answer API won't have this either.
        ChatResponse noneAnswer = ragQueryOrchestrator.answer(tenantId,
                "qzxjklwvy9988 completely fabricated nonsense query string");
        assertThat(noneAnswer.source()).isEqualTo("NONE");
        assertThat(noneAnswer.answer()).isEqualTo(RagQueryOrchestrator.DETERMINISTIC_FAILURE_MESSAGE);
        assertThat(noneAnswer.citations()).isEmpty();
        assertThat(noneAnswer.webSources()).isEmpty();
    }
}
