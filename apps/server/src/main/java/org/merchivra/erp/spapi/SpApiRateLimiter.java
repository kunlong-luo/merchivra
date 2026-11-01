package org.merchivra.erp.spapi;

/**
 * Fail-closed admission boundary for official Amazon SDK calls.
 * A successful permit is consumed even if the subsequent request fails.
 */
@FunctionalInterface
public interface SpApiRateLimiter {
    void requirePermit(SpApiSellerScope seller, Class<?> apiFamily);

    /** Pre-verification Seller ID is unknown; enforce a region-wide safety gate. */
    default void requireVerificationPermit(SpApiRegion region) { }

    static SpApiRateLimiter disabled() {
        return (seller, family) -> { };
    }
}
