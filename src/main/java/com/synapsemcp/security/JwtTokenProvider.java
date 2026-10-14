package com.synapsemcp.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.synapsemcp.auth.entity.UserRole;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** HS256 JWT issuance and validation (Nimbus JOSE via Spring Security). */
@Component
public class JwtTokenProvider {

    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration expiration;
    private final String issuer;

    public JwtTokenProvider(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("synapse.security.jwt.secret (JWT_SECRET) must be set");
        }
        byte[] keyBytes = Base64.getDecoder().decode(properties.secret());
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT secret must be at least " + MIN_SECRET_BYTES + " bytes (Base64-encoded)");
        }
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbusDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        this.decoder = nimbusDecoder;
        this.expiration = properties.expiration();
        this.issuer = properties.issuer();
    }

    public String generateToken(UUID userId, UUID tenantId, String email, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(expiration))
                .claim(CLAIM_TENANT_ID, tenantId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** @return the principal for a valid, unexpired token; empty for anything malformed, forged or expired. */
    public Optional<AuthenticatedPrincipal> parse(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return Optional.of(new AuthenticatedPrincipal(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT_ID)),
                    jwt.getClaimAsString(CLAIM_EMAIL),
                    jwt.getClaimAsString(CLAIM_ROLE)));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }
}
