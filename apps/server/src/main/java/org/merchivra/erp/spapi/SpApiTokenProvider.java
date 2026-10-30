package org.merchivra.erp.spapi;

/**
 * Refresh token lookup MUST be from an approved per-seller secret store.
 * This interface only exchanges a token; it does not provision or persist one.
 */
@FunctionalInterface
public interface SpApiTokenProvider {
    String accessToken(String refreshToken) throws Exception;
}
