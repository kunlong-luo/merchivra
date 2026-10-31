package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe minimum-PII page transport. Only a server-selected and authorized
 * marketplace can be fetched; never accept refresh tokens or URLs from HTTP.
 */
public interface OrdersSyncSource {
    record OrderFact(String orderId, String marketplaceCode, Instant updatedAt, String fulfillment) {}
    record Page(List<OrderFact> orders, String nextToken) {}
    record Scope(UUID tenant, UUID marketplace, String marketplaceCode) {}

    /** Re-check source eligibility immediately before fetching and before DB commit. */
    void requireAuthorized(Scope scope);
    Page fetch(Scope scope, Instant after, Instant before, String cursor) throws Exception;
    String dataOrigin();
    boolean allowed();
}
