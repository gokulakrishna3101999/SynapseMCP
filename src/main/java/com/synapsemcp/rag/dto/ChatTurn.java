package com.synapsemcp.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One prior turn of a conversation, sent by the client on each request (architecture.md: chat history is
 * held client-side only, never persisted server-side - this is how that stays true while still letting
 * the backend use recent context for retrieval and answer phrasing within a single request).
 */
public record ChatTurn(
        @NotBlank @Pattern(regexp = "user|assistant") String role,
        @NotBlank String text) {
}
