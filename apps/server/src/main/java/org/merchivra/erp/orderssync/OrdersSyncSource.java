package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe minimum-PII page transport. Only a server-selected and authorized
 * marketplace can be fetched; never accept refresh tokens or URLs from HTTP.
 */
public interface OrdersSyncSource {
    /** Source item fields only. Pending/omitted proceeds remain null, never zero. */
    record ItemFact(String itemId,String sellerSku,Integer quantity,
                    String itemSubtotal,String currency) {}
    /**
     * itemsObserved=false means the API omitted the orderItems dataset;
     * that must never overwrite previously observed lines.
     */
    record OrderFact(String orderId, String marketplaceCode, Instant updatedAt,
                     String fulfillment, List<ItemFact> items, boolean itemsObserved) {
        public OrderFact(String orderId,String marketplaceCode,Instant updatedAt,String fulfillment) {
            this(orderId,marketplaceCode,updatedAt,fulfillment,List.of(),false);
        }
    }
    record Page(List<OrderFact> orders, String nextToken) {}
    record Scope(UUID tenant, UUID marketplace, String marketplaceCode) {}

    /** Re-check source eligibility immediately before fetching and before DB commit. */
    void requireAuthorized(Scope scope);
    Page fetch(Scope scope, Instant after, Instant before, String cursor) throws Exception;
    String dataOrigin();
    boolean allowed();
}
