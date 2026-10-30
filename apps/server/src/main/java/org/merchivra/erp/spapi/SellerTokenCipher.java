package org.merchivra.erp.spapi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with per-record random 96-bit nonce and AAD bound to tenant,
 * account, Seller ID and key version. Key bytes are supplied by deployment's
 * secret manager/environment and never persisted to PostgreSQL.
 */
public final class SellerTokenCipher {
    public record Sealed(byte[] ciphertext, byte[] nonce, int version) {}
    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int version;

    public SellerTokenCipher(String base64Key, int version) {
        if (version < 1) throw new IllegalArgumentException("Key version must be positive");
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(base64Key); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid encryption key encoding"); }
        if (decoded.length != 32) throw new IllegalArgumentException("AES-256 key requires 32 bytes");
        this.key = decoded.clone();
        this.version = version;
    }

    public Sealed encrypt(UUID tenant, UUID account, String sellerId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank())
            throw new IllegalArgumentException("Empty Seller grant");
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(tenant, account, sellerId, version));
            byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
            return new Sealed(encrypted, nonce, version);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Unable to encrypt Seller token"); }
    }

    public String decrypt(UUID tenant, UUID account, String sellerId, Sealed stored) {
        if (stored.version() != version) throw new IllegalStateException("Unknown vault key version");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, stored.nonce()));
            cipher.updateAAD(aad(tenant, account, sellerId, stored.version()));
            return new String(cipher.doFinal(stored.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Invalid Seller vault authentication tag");
        }
    }

    private static byte[] aad(UUID tenant, UUID account, String sellerId, int version) {
        return ("merchivra:spapi:v" + version + ":" + tenant + ":" + account + ":" + sellerId)
            .getBytes(StandardCharsets.UTF_8);
    }
}
