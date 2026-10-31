package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiPayloadDecoder;
import org.merchivra.erp.spapi.SpApiReadOperations;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11D trusted Orders read seam: no HTTP route, no direct Seller proxy, and no
 * connection to V11A's MOCK-only job writer. A trusted worker may call this
 * only after a separately audited app-role review is recorded.
 *
 * Uses the reviewed Orders 2026-01-01 read operation, never PII includedData.
 * Does not persist source JSON, buyer PII or settlement facts. Typed v2026
 * path extracts non-PII item facts and optional ITEM proceeds subtotal only.
 */
public final class ApprovedOrdersReadGateway {
    private final JdbcTemplate jdbc;
    private final SpApiSellerGrantVault vault;
    private final SpApiReadOperations api;
    private final SpApiPayloadDecoder decoder;
    private final TypedOrdersSdkReader typed;

    public ApprovedOrdersReadGateway(JdbcTemplate jdbc, SpApiSellerGrantVault vault,
                                     SpApiReadOperations api, SpApiPayloadDecoder decoder) {
        this.jdbc=jdbc; this.vault=vault; this.api=api; this.decoder=decoder;
        this.typed=null;
    }

    /** V11D LIVE requests use Amazon's typed SDK, not raw-JSON transport. */
    public ApprovedOrdersReadGateway(JdbcTemplate jdbc, SpApiSellerGrantVault vault,
                                     TypedOrdersSdkReader typed) {
        this.jdbc=jdbc; this.vault=vault; this.api=null; this.decoder=null;
        this.typed=java.util.Objects.requireNonNull(typed);
    }

    private record Marketplace(UUID sellerAccount, String site) {}

    private Marketplace marketplace(UUID tenant, UUID marketplaceId) {
        if (tenant==null || marketplaceId==null)
            throw new SecurityException("Server-side tenant and Marketplace are required");
        List<Marketplace> rows=jdbc.query("""
            SELECT m.seller_account_id,m.marketplace_id
            FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id
            JOIN seller_account s ON s.tenant_id=m.tenant_id
                AND s.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
              AND t.status='ACTIVE' AND s.connection_status='CONNECTED'
            """, (rs,n)->new Marketplace(rs.getObject(1,UUID.class),rs.getString(2)),
            tenant,marketplaceId);
        if(rows.size()!=1)throw new SecurityException("Approved Marketplace unavailable");
        return rows.getFirst();
    }

    private SpApiSellerScope approved(UUID tenant, Marketplace site) {
        return vault.loadOrdersApproved(tenant,site.sellerAccount(),site.site())
            .orElseThrow(()->new SecurityException(
                "Independent Orders role evidence or Seller grant missing"));
    }

    /**
     * Read-only preflight. Called at claim, fetch and under the worker's
     * transaction-held database locks before an Orders page is committed.
     * Never returns a refresh token to callers.
     */
    public void requireAuthorized(UUID tenant, UUID marketplaceId, String expectedSite) {
        Marketplace resolved=marketplace(tenant,marketplaceId);
        if(!resolved.site().equals(expectedSite))
            throw new SecurityException("Marketplace scope changed");
        approved(tenant,resolved);
    }

    public OrdersSyncSource.Page search(UUID tenant, UUID marketplaceId, Instant after,
        Instant before, String cursor) throws Exception {
        if(after==null || before==null || !after.isBefore(before))
            throw new IllegalArgumentException("Orders search time window required");
        if(cursor!=null && (cursor.isBlank() || cursor.length()>4096))
            throw new IllegalArgumentException("Invalid opaque pagination cursor");
        Marketplace site=marketplace(tenant,marketplaceId);
        SpApiSellerScope scope=approved(tenant,site);
        // The existing reviewed client sends only lastUpdated* and
        // marketplaceIds. It never requests BUYER or RECIPIENT.
        var parsed=typed==null
            ? decoder.orders2026(api.searchOrders(scope,after,before,cursor).body())
            : typed.search(scope,after,before,cursor);
        if(parsed.orders().size()>100 || (parsed.nextToken()!=null
            && (parsed.nextToken().isBlank() || parsed.nextToken().length()>4096)))
            throw new IllegalArgumentException("Invalid Orders page shape");
        var facts=parsed.orders().stream().map(order -> {
            if(order.orderId()==null || order.orderId().isBlank()
               || order.orderId().length()>160
               || !site.site().equals(order.marketplaceId())
               || order.lastUpdated()==null
               || order.fulfillmentRaw()==null || order.fulfillmentRaw().length()>100)
                throw new IllegalArgumentException("Orders response scope mismatch");
            return new OrdersSyncSource.OrderFact(order.orderId(),site.site(),
                order.lastUpdated(),order.fulfillmentRaw());
        }).toList();
        // Revocation/role change during network read MUST prevent return.
        Marketplace current=marketplace(tenant,marketplaceId);
        SpApiSellerScope stillApproved=approved(tenant,current);
        if(!current.equals(site) || !stillApproved.sellerId().equals(scope.sellerId())
            || stillApproved.region()!=scope.region())
            throw new SecurityException("Seller authorization changed during Orders read");
        return new OrdersSyncSource.Page(facts,parsed.nextToken());
    }
}
