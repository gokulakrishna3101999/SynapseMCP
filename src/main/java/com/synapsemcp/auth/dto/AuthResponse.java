package com.synapsemcp.auth.dto;

import java.util.UUID;

public record AuthResponse(String token, UserInfo user, UUID knowledgeBaseId) {

    public record UserInfo(UUID id, String email, String fullName) {
    }
}
