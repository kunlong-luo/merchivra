package org.merchivra.erp.inventory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.purchasing.PurchasingService;
import org.merchivra.erp.purchasing.SupplierPurchasePlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit owner action only. Rechecks the selected inventory observation, active SKU
 * mapping and policy before creating an ordinary local DRAFT. No Amazon mutations.
 */
@Service
public class ReplenishmentPurchaseDraftService {
    private final JdbcTemplate jdbc;
    private final PurchasingService purchasing;
    private final Clock clock;

    @Autowired
    public ReplenishmentPurchaseDraftService(JdbcTemplate jdbc,PurchasingService purchasing) {
        this(jdbc,purchasing,Clock.systemUTC());
    }
    ReplenishmentPurchaseDraftService(JdbcTemplate jdbc,PurchasingService purchasing,Clock clock) {
        this.jdbc=jdbc;
        this.purchasing=purchasing;
        this.clock=clock;
    }

    public record Request(UUID requestKey,String sellerSku,UUID supplierId,String currency,
                          String unitCost,String expectedPolicyUpdatedAt,
                          UUID expectedObservationId,int expectedQuantity,
                          String expectedSupplierRuleUpdatedAt,Integer expectedOrderQuantity) {
        /** Compatible original V37 manual-price request; no supplier quote proof. */
        public Request(UUID key,String sku,UUID supplier,String currency,String unitCost,
                       String policyUpdatedAt,UUID observation,int expectedQuantity) {
            this(key,sku,supplier,currency,unitCost,policyUpdatedAt,
                observation,expectedQuantity,null,null);
        }
    }
    public record Origin(String purchaseOrderId,String orderNumber,String status,
                         String sellerSku,String productSkuId,int fulfillable,
                         int suggestedQuantity,int orderedQuantity,String currency,String unitCost,
                         String pricingSource,String supplierRuleUpdatedAt,
                         Integer minimumOrderQuantity,Integer packMultiple,
                         Integer supplierLeadTimeDays,String observedAt,String createdAt) {}

    private record Policy(int reorderPoint,int targetStock,int leadTimeDays,Instant updatedAt) {}
    private record Observation(UUID id,String quality,Integer fulfillable,
                               Integer reserved,Instant observedAt) {}
    private record Saved(UUID orderId,UUID marketplaceId,String sellerSku,UUID supplierId,
                         String currency,BigDecimal unitCost,Instant policyUpdatedAt,
                         UUID observationId,int suggestedQuantity,int orderedQuantity,
                         Instant supplierRuleUpdatedAt,UUID createdBy) {}
    private record SupplierRule(Instant updatedAt,int moq,int pack,int leadDays,
                                String currency,BigDecimal unitCost) {}

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid purchase draft request");
    }
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Replenishment evidence or SKU mapping changed; refresh suggestions");
    }
    private static Instant requiredInstant(String value) {
        try { return Instant.parse(value); }
        catch (NullPointerException|DateTimeParseException ex) { throw invalid(); }
    }
    private static BigDecimal requiredCost(String value) {
        if(value==null || !value.matches("[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw invalid();
        return new BigDecimal(value);
    }
    private static void validate(Request req) {
        if(req==null || req.requestKey()==null || req.supplierId()==null
           || req.expectedObservationId()==null || req.sellerSku()==null
           || req.sellerSku().isBlank() || req.sellerSku().length()>200
           || req.sellerSku().chars().anyMatch(Character::isISOControl)
           || req.currency()==null || !req.currency().matches("[A-Z]{3}")
           || req.expectedQuantity()<1 || req.expectedQuantity()>1_000_000
           || (req.expectedSupplierRuleUpdatedAt()==null)!=(req.expectedOrderQuantity()==null)
           || (req.expectedOrderQuantity()!=null &&
              (req.expectedOrderQuantity()<req.expectedQuantity()
               || req.expectedOrderQuantity()>1_000_000)))
            throw invalid();
    }
    private static Instant asInstant(java.sql.ResultSet rs,String column)
        throws java.sql.SQLException {
        OffsetDateTime v=rs.getObject(column,OffsetDateTime.class);
        return v==null?null:v.toInstant();
    }
    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);
    }

    @Transactional
    public PurchasingService.Detail create(UUID tenant,UUID actor,UUID marketplace,Request req) {
        validate(req);
        Instant expectedPolicy=requiredInstant(req.expectedPolicyUpdatedAt());
        BigDecimal unitCost=requiredCost(req.unitCost());
        boolean quoteBacked=req.expectedSupplierRuleUpdatedAt()!=null;
        Instant supplierVersion=quoteBacked?
            requiredInstant(req.expectedSupplierRuleUpdatedAt()):null;
        int orderQuantity=quoteBacked?req.expectedOrderQuantity():req.expectedQuantity();

        // Serialize idempotency-key checks across concurrent requests of the same tenant.
        List<UUID> active=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(active.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        List<Saved> existing=jdbc.query("""
            SELECT purchase_order_id,seller_marketplace_id,seller_sku,supplier_id,
                currency_code,unit_cost,policy_updated_at,observation_id,
                suggested_quantity,ordered_quantity,supplier_rule_updated_at,created_by
            FROM erp_replenishment_purchase_draft_source
            WHERE tenant_id=? AND request_key=?
            """,(rs,i)->new Saved(
                rs.getObject("purchase_order_id",UUID.class),
                rs.getObject("seller_marketplace_id",UUID.class),
                rs.getString("seller_sku"),rs.getObject("supplier_id",UUID.class),
                rs.getString("currency_code").trim(),rs.getBigDecimal("unit_cost"),
                asInstant(rs,"policy_updated_at"),
                rs.getObject("observation_id",UUID.class),
                rs.getInt("suggested_quantity"),rs.getInt("ordered_quantity"),
                asInstant(rs,"supplier_rule_updated_at"),
                rs.getObject("created_by",UUID.class)),
            tenant,req.requestKey());
        if(!existing.isEmpty()) {
            Saved old=existing.getFirst();
            if(!old.marketplaceId().equals(marketplace)
               || !old.sellerSku().equals(req.sellerSku())
               || !old.supplierId().equals(req.supplierId())
               || !old.currency().equals(req.currency())
               || old.unitCost().compareTo(unitCost)!=0
               || !old.policyUpdatedAt().equals(expectedPolicy)
               || !old.observationId().equals(req.expectedObservationId())
               || old.suggestedQuantity()!=req.expectedQuantity()
               || old.orderedQuantity()!=orderQuantity
               || !java.util.Objects.equals(old.supplierRuleUpdatedAt(),supplierVersion)
               || !old.createdBy().equals(actor))throw new ResponseStatusException(
                   HttpStatus.CONFLICT,"Request key already used for a different draft");
            return purchasing.detail(tenant,old.orderId());
        }

        // Different request keys cannot create multiple drafts from one observed snapshot.
        // This check is serialized by the tenant lock; V38 also enforces it in PostgreSQL.
        Integer alreadyDrafted=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_replenishment_purchase_draft_source
            WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku=?
              AND observation_id=?
            """,Integer.class,tenant,marketplace,req.sellerSku(),req.expectedObservationId());
        if(alreadyDrafted!=null && alreadyDrafted>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This inventory observation already generated a purchase draft");

        // Catalog mapping can be edited only with an exclusive lock on this Listing.
        // An ACTIVE mapping alone is insufficient if the internal SKU was archived.
        List<UUID> mapped=jdbc.query("""
            SELECT m.product_sku_id
            FROM marketplace_listing l
            JOIN listing_sku_mapping m ON m.tenant_id=l.tenant_id
                AND m.listing_id=l.id AND m.status='ACTIVE' AND m.valid_to IS NULL
            JOIN product_sku s ON s.tenant_id=l.tenant_id
                AND s.id=m.product_sku_id AND s.status='ACTIVE'
            WHERE l.tenant_id=? AND l.seller_marketplace_id=? AND l.seller_sku=?
              AND l.mapping_status='ACTIVE'
            FOR SHARE OF l,s
            """,(rs,i)->rs.getObject(1,UUID.class),tenant,marketplace,req.sellerSku());
        if(mapped.size()!=1)throw stale();

        List<Policy> policies=jdbc.query("""
            SELECT reorder_point,target_stock,lead_time_days,updated_at
            FROM erp_replenishment_policy
            WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku=?
            FOR SHARE
            """,(rs,i)->new Policy(rs.getInt("reorder_point"),rs.getInt("target_stock"),
                rs.getInt("lead_time_days"),asInstant(rs,"updated_at")),
            tenant,marketplace,req.sellerSku());
        if(policies.size()!=1)throw stale();
        Policy policy=policies.getFirst();
        if(!policy.updatedAt().equals(expectedPolicy))throw stale();

        List<Observation> latest=jdbc.query("""
            SELECT id,quality,fulfillable,reserved,observed_at
            FROM fba_inventory_observation
            WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku_raw=?
            ORDER BY observed_at DESC NULLS LAST,ingested_at DESC,id DESC LIMIT 1
            """,(rs,i)->new Observation(rs.getObject("id",UUID.class),
                rs.getString("quality"),rs.getObject("fulfillable",Integer.class),
                rs.getObject("reserved",Integer.class),asInstant(rs,"observed_at")),
            tenant,marketplace,req.sellerSku());
        if(latest.isEmpty())throw stale();
        Observation observation=latest.getFirst();
        var decision=ReplenishmentService.evaluate(observation.quality(),
            observation.fulfillable(),observation.reserved(),observation.observedAt(),
            policy.reorderPoint(),policy.targetStock(),clock.instant());
        if(!observation.id().equals(req.expectedObservationId())
           || !"REORDER".equals(decision.state())
           || decision.suggestedQuantity()==null
           || decision.suggestedQuantity()!=req.expectedQuantity()
           || observation.observedAt()==null)throw stale();

        SupplierRule supplierRule=null;
        if(quoteBacked) {
            List<SupplierRule> actual=jdbc.query("""
                SELECT r.updated_at,r.minimum_order_quantity,r.pack_multiple,
                    r.lead_time_days,r.currency_code,r.unit_cost
                FROM erp_supplier_sku_rule r JOIN erp_supplier sup
                  ON sup.tenant_id=r.tenant_id AND sup.id=r.supplier_id
                  AND sup.status='ACTIVE'
                WHERE r.tenant_id=? AND r.supplier_id=? AND r.product_sku_id=?
                FOR SHARE OF r,sup
                """,(rs,i)->new SupplierRule(asInstant(rs,"updated_at"),
                    rs.getInt("minimum_order_quantity"),rs.getInt("pack_multiple"),
                    rs.getInt("lead_time_days"),rs.getString("currency_code").trim(),
                    rs.getBigDecimal("unit_cost")),tenant,req.supplierId(),mapped.getFirst());
            if(actual.size()!=1)throw stale();
            supplierRule=actual.getFirst();
            if(!supplierRule.updatedAt().equals(supplierVersion)
               || !supplierRule.currency().equals(req.currency())
               || supplierRule.unitCost().compareTo(unitCost)!=0
               || SupplierPurchasePlanService.orderQuantity(req.expectedQuantity(),
                    supplierRule.moq(),supplierRule.pack())!=orderQuantity)
                throw stale();
        }

        PurchasingService.Detail draft=purchasing.create(tenant,actor,req.supplierId(),
            req.currency(),"本地 FBA 补货建议草稿；需另行审批，不代表 Amazon 入仓",
            List.of(new PurchasingService.RequestedLine(mapped.getFirst(),
                orderQuantity,unitCost.toPlainString())));
        jdbc.update("""
            INSERT INTO erp_replenishment_purchase_draft_source
              (tenant_id,request_key,purchase_order_id,seller_marketplace_id,seller_sku,
               product_sku_id,supplier_id,currency_code,unit_cost,policy_updated_at,
               observation_id,observed_at,fulfillable,reorder_point,target_stock,
               lead_time_days,suggested_quantity,created_by,ordered_quantity,
               supplier_rule_updated_at,minimum_order_quantity,pack_multiple,
               supplier_lead_time_days,supplier_pricing_source)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,tenant,req.requestKey(),UUID.fromString(draft.order().id()),
            marketplace,req.sellerSku(),mapped.getFirst(),req.supplierId(),
            req.currency(),unitCost,utc(policy.updatedAt()),observation.id(),
            utc(observation.observedAt()),observation.fulfillable(),
            policy.reorderPoint(),policy.targetStock(),policy.leadTimeDays(),
            req.expectedQuantity(),actor,orderQuantity,
            supplierVersion==null?null:utc(supplierVersion),supplierRule==null?null:supplierRule.moq(),
            supplierRule==null?null:supplierRule.pack(),
            supplierRule==null?null:supplierRule.leadDays(),
            quoteBacked?"V42_RULE":"MANUAL");
        return draft;
    }

    public List<Origin> history(UUID tenant,UUID marketplace) {
        return jdbc.query("""
            SELECT d.purchase_order_id,o.order_number,o.status,d.seller_sku,
                d.product_sku_id,d.fulfillable,d.suggested_quantity,d.ordered_quantity,
                d.currency_code,d.unit_cost,d.supplier_pricing_source,
                d.supplier_rule_updated_at,d.minimum_order_quantity,
                d.pack_multiple,d.supplier_lead_time_days,
                d.observed_at,d.created_at
            FROM erp_replenishment_purchase_draft_source d
            JOIN erp_purchase_order o ON o.tenant_id=d.tenant_id
                AND o.id=d.purchase_order_id
            WHERE d.tenant_id=? AND d.seller_marketplace_id=?
            ORDER BY d.created_at DESC,d.request_key DESC LIMIT 100
            """,(rs,i)->new Origin(rs.getObject("purchase_order_id",UUID.class).toString(),
                rs.getString("order_number"),rs.getString("status"),rs.getString("seller_sku"),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getInt("fulfillable"),rs.getInt("suggested_quantity"),
                rs.getInt("ordered_quantity"),rs.getString("currency_code").trim(),
                rs.getBigDecimal("unit_cost").toPlainString(),
                rs.getString("supplier_pricing_source"),
                asInstant(rs,"supplier_rule_updated_at")==null?null:
                    asInstant(rs,"supplier_rule_updated_at").toString(),
                rs.getObject("minimum_order_quantity",Integer.class),
                rs.getObject("pack_multiple",Integer.class),
                rs.getObject("supplier_lead_time_days",Integer.class),
                asInstant(rs,"observed_at").toString(),asInstant(rs,"created_at").toString()),
            tenant,marketplace);
    }
}
