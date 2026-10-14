package com.synapsemcp.security;

import com.synapsemcp.auth.entity.UserRole;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static JwtTokenProvider provider(String secret, Duration ttl) {
        return new JwtTokenProvider(new JwtProperties(secret, ttl, "synapse-mcp"));
    }

    @Test
    void roundTripsClaims() {
        JwtTokenProvider provider = provider(secret(), Duration.ofHours(1));
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = provider.generateToken(userId, tenantId, "a@b.com", UserRole.USER);
        AuthenticatedPrincipal principal = provider.parse(token).orElseThrow();

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.tenantId()).isEqualTo(tenantId);
        assertThat(principal.email()).isEqualTo("a@b.com");
        assertThat(principal.role()).isEqualTo("USER");
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = provider(secret(), Duration.ofHours(1))
                .generateToken(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", UserRole.USER);

        assertThat(provider(secret(), Duration.ofHours(1)).parse(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        String secret = secret();
        var key = new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        var encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
        java.time.Instant issued = java.time.Instant.now().minus(Duration.ofHours(2));
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer("synapse-mcp").subject(UUID.randomUUID().toString())
                .issuedAt(issued).expiresAt(issued.plus(Duration.ofMinutes(5)))
                .claim("tenantId", UUID.randomUUID().toString()).claim("email", "a@b.com").claim("role", "USER")
                .build();
        String expired = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThat(provider(secret, Duration.ofHours(1)).parse(expired)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(provider(secret(), Duration.ofHours(1)).parse("not.a.jwt")).isEmpty();
    }

    @Test
    void failsFastOnMissingOrWeakSecret() {
        assertThatThrownBy(() -> provider("", Duration.ofHours(1))).isInstanceOf(IllegalStateException.class);
        String weak = Base64.getEncoder().encodeToString(new byte[8]);
        assertThatThrownBy(() -> provider(weak, Duration.ofHours(1))).isInstanceOf(IllegalStateException.class);
    }
}
