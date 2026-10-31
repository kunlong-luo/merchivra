package org.merchivra.erp.spapi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned AES-256-GCM envelope for Seller refresh tokens.
 * Keys come from the deployment secret manager; only ciphertext, nonce and
 * key_version are stored in PostgreSQL. Retain old keys until rewrap is
 * confirmed for EVERY non-revoked grant, including pending grants.
 */
public final class SellerTokenCipher {
    public record Sealed(byte[] ciphertext, byte[] nonce, int version) {}
    private final Map<Integer, SecretKeySpec> keys;
    private final int activeVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Compatibility for the V7 deployment with exactly one key. */
    public SellerTokenCipher(String base64Key, int version) {
        this(Map.of(version, base64Key), version);
    }

    public SellerTokenCipher(Map<Integer,String> keyring, int activeVersion) {
        if (activeVersion < 1 || keyring == null || keyring.isEmpty()
            || !keyring.containsKey(activeVersion)) {
            throw new IllegalArgumentException("Active vault key version unavailable");
        }
        var parsed = new HashMap<Integer, SecretKeySpec>();
        keyring.forEach((version, encoded) -> {
            if (version == null || version < 1 || encoded == null)
                throw new IllegalArgumentException("Invalid vault key version");
            byte[] key;
            try { key = Base64.getDecoder().decode(encoded); }
            catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid encryption key encoding");
            }
            if (key.length != 32) throw new IllegalArgumentException("AES-256 key requires 32 bytes");
            parsed.put(version, new SecretKeySpec(key, "AES"));
            java.util.Arrays.fill(key, (byte) 0);
        });
        this.keys = Map.copyOf(parsed);
        this.activeVersion = activeVersion;
    }

    /**
     * Keyring format: "1:base64,2:base64"; reject duplicate versions,
     * missing active key, or simultaneous legacy/keyring config. No secret logs.
     */
    public static SellerTokenCipher configured(String keyring, String legacyKey,
                                               int activeVersion) {
        if (keyring == null || keyring.isBlank()) {
            if (activeVersion != 1)
                throw new IllegalArgumentException("Non-v1 key requires versioned keyring");
            return new SellerTokenCipher(legacyKey, 1);
        }
        if (legacyKey != null && !legacyKey.isBlank())
            throw new IllegalArgumentException("Use either legacy key or versioned keyring");
        Map<Integer,String> entries = new HashMap<>();
        for (String item : keyring.split(",", -1)) {
            String[] parts = item.trim().split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[1-9][0-9]{0,8}")
                || parts[1].isBlank())
                throw new IllegalArgumentException("Invalid vault keyring entry");
            int version = Integer.parseInt(parts[0]);
            if (entries.putIfAbsent(version, parts[1]) != null)
                throw new IllegalArgumentException("Duplicate vault key version");
        }
        return new SellerTokenCipher(entries, activeVersion);
    }

    public int activeVersion() { return activeVersion; }

    public Sealed encrypt(UUID tenant, UUID account, String sellerId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank())
            throw new IllegalArgumentException("Empty Seller grant");
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion),
                new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(tenant, account, sellerId, activeVersion));
            byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
            return new Sealed(encrypted, nonce, activeVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt Seller token");
        }
    }

    public String decrypt(UUID tenant, UUID account, String sellerId, Sealed stored) {
        if (stored == null || stored.version() < 1 || !keys.containsKey(stored.version()))
            throw new IllegalStateException("Unknown vault key version");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(stored.version()),
                new GCMParameterSpec(128, stored.nonce()));
            cipher.updateAAD(aad(tenant, account, sellerId, stored.version()));
            return new String(cipher.doFinal(stored.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecurityException("Invalid Seller vault authentication tag");
        }
    }

    private static byte[] aad(UUID tenant, UUID account, String sellerId, int version) {
        return ("merchivra:spapi:v" + version + ":" + tenant + ":" + account + ":" + sellerId)
            .getBytes(StandardCharsets.UTF_8);
    }
}
