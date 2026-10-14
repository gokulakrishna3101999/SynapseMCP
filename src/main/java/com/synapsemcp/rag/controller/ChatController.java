package com.synapsemcp.rag.controller;

import com.synapsemcp.rag.dto.ChatRequest;
import com.synapsemcp.rag.dto.ChatResponse;
import com.synapsemcp.rag.service.RagQueryOrchestrator;
import com.synapsemcp.security.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final RagQueryOrchestrator orchestrator;

    public ChatController(RagQueryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return orchestrator.answer(tenantId, request.query(), request.history());
    }
}
