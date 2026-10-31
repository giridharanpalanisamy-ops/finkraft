package com.finkraft.management.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** API keys are shown once at creation and stored only as SHA-256 hashes. */
public final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "fk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
