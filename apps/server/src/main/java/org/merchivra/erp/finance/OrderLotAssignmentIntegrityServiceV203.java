package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V203 independent read-time check of V106 local sale-to-pick claims.
 * The database view recomputes item/pick quantities and source identity; the
 * already-existing V200 cost engine separately checks time-valid sale SKU mapping.
 * Neither can verify the identity of an external warehouse operator or Amazon
 * fulfillment, and neither is a cost posting permission.
 */
@Service
public class OrderLotAssignmentIntegrityServiceV203 {
    static final String INTEGRITY_SQL = """
        SELECT v.claim_id,v.snapshot_external_item_id,
          s.internal_sku,v.assigned_quantity,v.order_item_allocated,
          v.pick_line_allocated,v.receipt_allocated,v.integrity_state,v.external_dispatch_ref,
          v.external_payload_sha256,v.receipt_id
        FROM finance_sale_lot_integrity_v106 v
        LEFT JOIN product_sku s
          ON s.tenant_id=v.tenant_id AND s.id=v.snapshot_product_sku_id
        WHERE v.tenant_id=? AND v.marketplace_id=? AND v.sales_order_id=?
        ORDER BY v.claim_id LIMIT 1001
        """;

    public record Claim(String claimId,String externalItemId,
        String localInternalSku,int assignedQuantity,
        long orderItemAllocated,long pickLineAllocated,long receiptAllocated,
        String integrityState,String externalDispatchRef,
        String externalPayloadSha256,String purchaseReceiptId,
        boolean saleLotAttributed,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}

    public record Preview(String orderId,String marketplaceId,
        String orderCostReadiness,String contractVersion,String sourceScope,
        List<Claim> claims,List<String> blockerCodes,
        boolean saleLotAttributed,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}

    private record RawClaim(UUID claimId,String externalItemId,String sku,
        int quantity,long orderAllocated,long pickAllocated,long receiptAllocated,String dbState,
        String dispatchRef,String payloadSha256,UUID receiptId) {}

    private final JdbcTemplate jdbc;
    private final OrderCostReadinessService orderCosts;

    public OrderLotAssignmentIntegrityServiceV203(JdbcTemplate jdbc,
        OrderCostReadinessService orderCosts) {
        this.jdbc=jdbc;
        this.orderCosts=orderCosts;
    }

    static String effectiveState(String dbState,String costReadiness,
        OrderCostEngine.Line saleLine,String localSku) {
        if(!"LOCAL_LINK_INTACT_UNVERIFIED".equals(dbState))return dbState;
        if(!"COGS_READY_PREVIEW".equals(costReadiness) || saleLine==null
            || !"READY".equals(saleLine.issue()) || saleLine.quantity()==null
            || saleLine.quantity()<=0) return "ORDER_COST_OR_ITEM_EVIDENCE_STALE";
        if(!Objects.equals(saleLine.internalSku(),localSku))
            return "SALE_SKU_MAPPING_STALE";
        return "LOCAL_LINK_INTACT_UNVERIFIED";
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID marketplace,UUID order) {
        // This check rejects cross-tenant, unknown or non-marketplace orders
        // BEFORE querying claims. No fallback to synthetic/example data.
        OrderCostEngine.Preview cost=orderCosts.preview(tenant,marketplace,order);
        Map<String,OrderCostEngine.Line> liveLines=new HashMap<>();
        for(var line:cost.items()) {
            if(liveLines.putIfAbsent(line.itemId(),line)!=null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ambiguous LIVE sale item identity");
        }
        var raw=jdbc.query(INTEGRITY_SQL,
            (rs,n)->new RawClaim(
                rs.getObject("claim_id",UUID.class),
                rs.getString("snapshot_external_item_id"),
                rs.getString("internal_sku"),
                rs.getInt("assigned_quantity"),
                rs.getLong("order_item_allocated"),
                rs.getLong("pick_line_allocated"),
                rs.getLong("receipt_allocated"),
                rs.getString("integrity_state"),
                rs.getString("external_dispatch_ref"),
                rs.getString("external_payload_sha256"),
                rs.getObject("receipt_id",UUID.class)),
            tenant,marketplace,order);
        if(raw.size()>1000)throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Unbounded sale-to-lot claim count");
        var blockers=new ArrayList<String>();
        blockers.add("EXTERNAL_WMS_OPERATOR_AND_PHYSICAL_DISPATCH_UNVERIFIED");
        blockers.add("AMAZON_FBA_FBM_FULFILLMENT_UNVERIFIED");
        blockers.add("G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED");
        if(!"COGS_READY_PREVIEW".equals(cost.readiness()))
            blockers.add("ORDER_HISTORICAL_SKU_COST_NOT_READY");
        if(raw.isEmpty())blockers.add("NO_SALE_TO_LOCAL_PICK_CLAIMS");

        var seen=new HashSet<UUID>();
        var claims=new ArrayList<Claim>();
        boolean anyInvalid=false;
        for(var r:raw) {
            if(r.claimId()==null || !seen.add(r.claimId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate or missing claim identity");
            String state=effectiveState(r.dbState(),cost.readiness(),
                liveLines.get(r.externalItemId()),r.sku());
            if(!"LOCAL_LINK_INTACT_UNVERIFIED".equals(state))anyInvalid=true;
            claims.add(new Claim(r.claimId().toString(),r.externalItemId(),
                r.sku(),r.quantity(),r.orderAllocated(),r.pickAllocated(),
                r.receiptAllocated(),
                state,r.dispatchRef(),r.payloadSha256(),
                r.receiptId().toString(),false,false,null));
        }
        if(anyInvalid)blockers.add("SALE_TO_PICK_CLAIM_STALE_RETRACTED_OR_CONFLICTING");
        return new Preview(order.toString(),marketplace.toString(),
            cost.readiness(),"V1","LOCAL_OUTBOUND_UNVERIFIED_EXTERNAL",
            List.copyOf(claims),List.copyOf(blockers),false,false,null);
    }
}
