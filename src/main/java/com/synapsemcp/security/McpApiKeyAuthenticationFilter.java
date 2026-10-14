package com.synapsemcp.security;

import com.synapsemcp.mcp.service.ApiKeyAuthenticator;
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

/** Authenticates external MCP clients on {@code /mcp/**} with {@code Authorization: Bearer syn_mcp_...} or {@code ?token=}. */
public class McpApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyAuthenticator apiKeyAuthenticator;

    public McpApiKeyAuthenticationFilter(ApiKeyAuthenticator apiKeyAuthenticator) {
        this.apiKeyAuthenticator = apiKeyAuthenticator;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Optional<String> key = BearerTokenExtractor.fromHeader(request)
                    .or(() -> BearerTokenExtractor.fromQueryParam(request));
            key.flatMap(apiKeyAuthenticator::authenticate).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_MCP")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                TenantContext.setTenantId(principal.tenantId());
            });
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
