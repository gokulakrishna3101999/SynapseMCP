package com.synapsemcp.mcp.controller;

import com.synapsemcp.mcp.dto.McpKeyCreateRequest;
import com.synapsemcp.mcp.dto.McpKeyCreatedResponse;
import com.synapsemcp.mcp.dto.McpKeySummary;
import com.synapsemcp.mcp.service.McpKeyService;
import com.synapsemcp.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Managed by the authenticated web user (JWT), not by MCP keys themselves - see design.md section 7.4. */
@RestController
@RequestMapping("/api/v1/keys/mcp")
public class McpKeyController {

    private final McpKeyService mcpKeyService;

    public McpKeyController(McpKeyService mcpKeyService) {
        this.mcpKeyService = mcpKeyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpKeyCreatedResponse create(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                         @RequestBody(required = false) McpKeyCreateRequest request) {
        String name = request == null ? null : request.name();
        return mcpKeyService.create(principal.tenantId(), principal.userId(), name);
    }

    @GetMapping
    public List<McpKeySummary> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return mcpKeyService.list(principal.tenantId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        mcpKeyService.revoke(principal.tenantId(), id);
    }
}
