package com.synapsemcp.mcp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ApiKeyHasher {

    public static final String KEY_PREFIX = "syn_mcp_";

    private ApiKeyHasher() {
    }

    /** @return lowercase hex SHA-256 (64 chars), matching {@code api_keys.key_hash}. */
    public static String sha256Hex(String plaintextKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plaintextKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
