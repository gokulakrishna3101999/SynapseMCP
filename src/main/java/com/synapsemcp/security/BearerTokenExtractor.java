package com.synapsemcp.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

final class BearerTokenExtractor {

    private static final String BEARER = "Bearer ";

    private BearerTokenExtractor() {
    }

    static Optional<String> fromHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = header.substring(BEARER.length()).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }
        return Optional.empty();
    }

    static Optional<String> fromQueryParam(HttpServletRequest request) {
        String token = request.getParameter("token");
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
    }
}
