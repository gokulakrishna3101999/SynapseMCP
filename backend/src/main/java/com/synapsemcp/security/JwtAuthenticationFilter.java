package com.synapsemcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Authenticates React client requests on {@code /api/v1/**}. Invalid or missing tokens simply leave the request
 * unauthenticated so the security entry point answers 401. The browser {@code EventSource} cannot set headers, so a
 * {@code ?token=} query parameter is accepted ONLY for the document progress stream.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern PROGRESS_STREAM = Pattern.compile("^/api/v1/documents/[^/]+/progress-stream$");

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/auth/");
    }

    /** SSE completes on an ASYNC dispatch; re-authenticate so the dispatch is not rejected. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolveToken(request).flatMap(tokenProvider::parse).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                TenantContext.setTenantId(principal.tenantId());
            });
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<String> resolveToken(HttpServletRequest request) {
        Optional<String> header = BearerTokenExtractor.fromHeader(request);
        if (header.isPresent()) {
            return header;
        }
        return PROGRESS_STREAM.matcher(request.getRequestURI()).matches()
                ? BearerTokenExtractor.fromQueryParam(request)
                : Optional.empty();
    }
}
