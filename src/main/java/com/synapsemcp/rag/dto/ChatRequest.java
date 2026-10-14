package com.synapsemcp.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /api/v1/chat request body, per design.md section 7.3. {@code history} is optional and client-held
 * only (the React app's own in-memory conversation) - the backend never persists it, only uses it
 * in-memory for this one request (RagQueryOrchestrator trims it further to the last few turns).
 */
public record ChatRequest(
        @NotBlank String query,
        @Valid @Size(max = 20) List<ChatTurn> history) {

    public ChatRequest {
        if (history == null) {
            history = List.of();
        }
    }
}
