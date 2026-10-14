package com.synapsemcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "synapse.security.jwt")
public record JwtProperties(String secret, Duration expiration, String issuer) {
}
