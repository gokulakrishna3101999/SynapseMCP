package com.synapsemcp.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated user's id, read from whichever filter (JWT or MCP key) populated the SecurityContext. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @return the current user's id, or {@code null} when unauthenticated (e.g. during signup/signin). */
    public static UUID userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal principal
                ? principal.userId()
                : null;
    }
}
