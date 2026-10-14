package com.synapsemcp.mcp.tools;

import com.synapsemcp.rag.dto.ChatResponse;
import com.synapsemcp.rag.service.RagQueryOrchestrator;
import com.synapsemcp.security.TenantContext;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * {@code query_user_rag}: the same local-first / web-fallback / deterministic-failure pipeline as
 * {@code POST /api/v1/chat} (architecture.md section 6.2) - reuses {@link RagQueryOrchestrator} directly
 * so external LLM clients and the web UI never diverge in behavior.
 */
@Component
public class QueryRagTool {

    private final RagQueryOrchestrator ragQueryOrchestrator;

    public QueryRagTool(RagQueryOrchestrator ragQueryOrchestrator) {
        this.ragQueryOrchestrator = ragQueryOrchestrator;
    }

    @McpTool(name = "query_user_rag",
            description = "Answers a question using the authenticated user's own knowledge base, falling back to "
                    + "a web search when local context is insufficient, with citations or a deterministic failure "
                    + "message when neither source has relevant information.")
    public ChatResponse query(
            @McpToolParam(description = "The natural-language question to answer", required = true) String query) {
        UUID tenantId = TenantContext.requireTenantId();
        return ragQueryOrchestrator.answer(tenantId, query);
    }
}
