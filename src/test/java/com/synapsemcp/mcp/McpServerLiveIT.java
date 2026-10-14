package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.synapsemcp.auth.dto.AuthRequests.SignupRequest;
import com.synapsemcp.auth.dto.AuthResponse;
import com.synapsemcp.auth.repository.TenantRepository;
import com.synapsemcp.auth.service.AuthService;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.DocumentStatus;
import com.synapsemcp.document.repository.DocumentRepository;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.ingestion.service.IngestionOrchestrator;
import com.synapsemcp.mcp.dto.McpKeyCreatedResponse;
import com.synapsemcp.mcp.service.McpKeyService;
import com.synapsemcp.security.TenantContext;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Drives the real MCP protocol over real SSE: a real API key authenticates a real
 * {@code McpSyncClient}, which lists and calls the 3 registered tools against real Postgres/S3/Gemini.
 * The key thing this proves that a unit test cannot: tenant isolation actually holds through the
 * {@code McpApiKeyAuthenticationFilter} -> {@code TenantContext} -> tool-method path, not just in the
 * services the tools happen to call (those already have their own tenant-isolation tests).
 * Run with: ./mvnw test -Dtest=McpServerLiveIT
 */
@EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerLiveIT {

    @LocalServerPort
    int port;

    @Autowired
    AuthService authService;
    @Autowired
    TenantRepository tenantRepository;
    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    McpKeyService mcpKeyService;
    @Autowired
    DocumentService documentService;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    IngestionOrchestrator ingestionOrchestrator;

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
        TenantContext.clear();
    }

    @Test
    void toolsAreRegisteredAndTenantScopedOverRealSse() {
        AuthResponse userA = authService.signup(new SignupRequest(
                "mcp-live-a-" + UUID.randomUUID() + "@example.com", "password1234", "Tenant A"));
        AuthResponse userB = authService.signup(new SignupRequest(
                "mcp-live-b-" + UUID.randomUUID() + "@example.com", "password1234", "Tenant B"));
        tenantAId = knowledgeBaseRepository.findById(userA.knowledgeBaseId()).orElseThrow().getTenantId();
        tenantBId = knowledgeBaseRepository.findById(userB.knowledgeBaseId()).orElseThrow().getTenantId();

        TenantContext.setTenantId(tenantAId);
        byte[] content = "The Atlas migration deadline is November 30th, with a frozen scope.".getBytes(StandardCharsets.UTF_8);
        UploadResponse upload = documentService.upload(
                new MockMultipartFile("file", "atlas-migration.txt", "text/plain", content));
        TenantContext.clear();
        ingestionOrchestrator.processDocumentAsync(upload.jobId(), upload.documentId(), tenantAId);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() ->
                documentRepository.findById(upload.documentId()).orElseThrow().getStatus() == DocumentStatus.INDEXED);

        McpKeyCreatedResponse keyA = mcpKeyService.create(tenantAId, userA.user().id(), "live-it-a");
        McpKeyCreatedResponse keyB = mcpKeyService.create(tenantBId, userB.user().id(), "live-it-b");

        try (McpSyncClient clientA = connect(keyA.key()); McpSyncClient clientB = connect(keyB.key())) {
            clientA.initialize();
            clientB.initialize();

            var tools = clientA.listTools().tools();
            assertThat(tools).extracting(Tool::name).contains(
                    "search_user_knowledge_base", "query_user_rag", "get_ingestion_status");

            CallToolResult statusResult = clientA.callTool(new CallToolRequest(
                    "get_ingestion_status", Map.of("documentId", upload.documentId().toString())));
            assertThat(statusResult.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(firstText(statusResult)).contains("INDEXED");

            CallToolResult searchAsOwner = clientA.callTool(new CallToolRequest(
                    "search_user_knowledge_base", Map.of("query", "Atlas migration deadline")));
            assertThat(firstText(searchAsOwner)).contains("Atlas");

            // Tenant isolation: tenant B's key must never see tenant A's document, even with the exact
            // query that matched perfectly for A.
            CallToolResult searchAsOtherTenant = clientB.callTool(new CallToolRequest(
                    "search_user_knowledge_base", Map.of("query", "Atlas migration deadline")));
            assertThat(firstText(searchAsOtherTenant)).doesNotContain("Atlas", "November");

            // get_ingestion_status for tenant B on tenant A's document id must fail, not leak the status.
            CallToolResult crossTenantStatus = clientB.callTool(new CallToolRequest(
                    "get_ingestion_status", Map.of("documentId", upload.documentId().toString())));
            assertThat(crossTenantStatus.isError()).isEqualTo(Boolean.TRUE);
        }
    }

    private McpSyncClient connect(String plaintextKey) {
        var transport = HttpClientSseClientTransport.builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse")
                .customizeRequest(b -> b.header("Authorization", "Bearer " + plaintextKey))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private static String firstText(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .findFirst()
                .orElse("");
    }
}
