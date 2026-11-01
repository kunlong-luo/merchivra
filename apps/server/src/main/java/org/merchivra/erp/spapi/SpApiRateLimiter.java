package org.merchivra.erp.spapi;

/**
 * Fail-closed admission boundary for official Amazon SDK calls.
 * A successful permit is consumed even if the subsequent request fails.
 */
@FunctionalInterface
public interface SpApiRateLimiter {
    void requirePermit(SpApiSellerScope seller, Class<?> apiFamily);

    static SpApiRateLimiter disabled() {
        return (seller, family) -> { };
    }
}
