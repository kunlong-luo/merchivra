package org.merchivra.erp.purchasing;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V42 LOCAL purchase planning. Supplier terms and warehouse observations are
 * evidence snapshots. Only an Owner action converts a plan into a local PO DRAFT;
 * no supplier order, FBA shipment, or Amazon API mutation takes place.
 */
@Service
public class SupplierPurchasePlanService {
    private final JdbcTemplate jdbc;
    private final PurchasingService purchasing;
    public SupplierPurchasePlanService(JdbcTemplate jdbc,PurchasingService purchasing) {
        this.jdbc=jdbc;
        this.purchasing=purchasing;
    }
    public record Rule(String supplierId,String productSkuId,int minimumOrderQuantity,
                       int packMultiple,int leadTimeDays,String currency,String unitCost,
                       String updatedAt) {}
    public record Recommendation(String warehouseId,String supplierId,String supplierName,
                                 String productSkuId,String internalSku,String sourceMovementId,
                                 String policyUpdatedAt,String ruleUpdatedAt,
                                 Long onHandQuantity,int reorderPoint,int targetStock,
                                 int minimumOrderQuantity,int packMultiple,int leadTimeDays,
                                 String currency,String unitCost,String state,
                                 Long suggestedQuantity,Long orderQuantity) {}
    public record Request(UUID requestKey,UUID warehouseId,UUID supplierId,UUID productSkuId,
                          UUID expectedLastMovementId,String expectedPolicyUpdatedAt,
                          String expectedRuleUpdatedAt,long expectedOnHand,
                          int expectedOrderQuantity) {}
    public record Plan(String id,String warehouseId,String supplierId,String productSkuId,
                       String sourceMovementId,String policyUpdatedAt,String ruleUpdatedAt,
                       long onHandQuantity,int reorderPoint,int targetStock,
                       int suggestedQuantity,int minimumOrderQuantity,int packMultiple,
                       int leadTimeDays,int orderedQuantity,String currency,String unitCost,
                       String estimatedArrivalDate,String status,String purchaseOrderId,
                       String createdBy,String createdAt) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Warehouse, supplier, SKU or plan unavailable");
    }
    private static String asInstant(ResultSet rs,String column) throws SQLException {
        return rs.getObject(column,OffsetDateTime.class).toInstant().toString();
    }
    private static BigDecimal validCost(String raw) {
        if(raw==null || !raw.matches("[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw bad("Invalid original-currency supplier unit cost");
        return new BigDecimal(raw);
    }
    private static void validTerms(int moq,int pack,int days,String currency) {
        if(moq<1 || moq>1_000_000 || pack<1 || pack>1_000_000
           || days<0 || days>365 || currency==null || !currency.matches("[A-Z]{3}"))
            throw bad("Invalid supplier MOQ, pack multiple, lead time or currency");
    }
    static long orderQuantity(long suggested,int moq,int pack) {
        if(suggested<1 || moq<1 || pack<1)
            throw bad("Invalid local purchasing quantity");
        long base=Math.max(suggested,moq);
        long rounded=((base+pack-1)/pack)*pack;
        if(rounded>1_000_000)
            throw conflict("MOQ and carton multiple exceed single PO line limit");
        return rounded;
    }
    private static Rule mapRule(ResultSet rs,int row) throws SQLException {
        return new Rule(rs.getObject("supplier_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getInt("minimum_order_quantity"),rs.getInt("pack_multiple"),
            rs.getInt("lead_time_days"),rs.getString("currency_code").trim(),
            rs.getBigDecimal("unit_cost").toPlainString(),asInstant(rs,"rule_updated_at"));
    }
    @Transactional(readOnly=true)
    public List<Rule> rules(UUID tenant) {
        return jdbc.query("""
            SELECT r.supplier_id,r.product_sku_id,r.minimum_order_quantity,
                   r.pack_multiple,r.lead_time_days,r.currency_code,r.unit_cost,
                   r.updated_at AS rule_updated_at
            FROM erp_supplier_sku_rule r
            WHERE r.tenant_id=?
            ORDER BY r.supplier_id,r.product_sku_id LIMIT 500
            """,SupplierPurchasePlanService::mapRule,tenant);
    }
    private void lockTenant(UUID tenant) {
        List<UUID> locked=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(locked.size()!=1)throw missing();
    }
    private void lockWarehouse(UUID tenant,UUID warehouse) {
        List<UUID> locked=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse WHERE tenant_id=? AND id=?
              AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant,warehouse);
        if(locked.size()!=1)throw missing();
    }
    @Transactional
    public Rule setRule(UUID tenant,UUID actor,UUID supplier,UUID sku,
                        int moq,int pack,int leadDays,String currency,String cost) {
        validTerms(moq,pack,leadDays,currency);
        BigDecimal price=validCost(cost);
        if(supplier==null || sku==null)throw bad("Supplier and internal SKU are required");
        lockTenant(tenant);
        List<UUID> supplierRows=jdbc.queryForList("""
            SELECT id FROM erp_supplier WHERE tenant_id=? AND id=? AND status='ACTIVE'
            FOR SHARE
            """,UUID.class,tenant,supplier);
        List<UUID> skuRows=jdbc.queryForList("""
            SELECT id FROM product_sku WHERE tenant_id=? AND id=? AND status='ACTIVE'
            FOR SHARE
            """,UUID.class,tenant,sku);
        if(supplierRows.size()!=1 || skuRows.size()!=1)throw missing();
        jdbc.update("""
            INSERT INTO erp_supplier_sku_rule
              (tenant_id,supplier_id,product_sku_id,minimum_order_quantity,
               pack_multiple,lead_time_days,currency_code,unit_cost,updated_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_id,supplier_id,product_sku_id) DO UPDATE SET
              minimum_order_quantity=EXCLUDED.minimum_order_quantity,
              pack_multiple=EXCLUDED.pack_multiple,lead_time_days=EXCLUDED.lead_time_days,
              currency_code=EXCLUDED.currency_code,unit_cost=EXCLUDED.unit_cost,
              updated_by=EXCLUDED.updated_by,
              updated_at=GREATEST(now(),erp_supplier_sku_rule.updated_at+interval '1 microsecond')
            """,tenant,supplier,sku,moq,pack,leadDays,currency,price,actor);
        auditRule(tenant,actor,supplier,sku,"RULE_SET");
        return jdbc.query("""
            SELECT supplier_id,product_sku_id,minimum_order_quantity,
                   pack_multiple,lead_time_days,currency_code,unit_cost,
                   updated_at AS rule_updated_at
            FROM erp_supplier_sku_rule
            WHERE tenant_id=? AND supplier_id=? AND product_sku_id=?
            """,SupplierPurchasePlanService::mapRule,tenant,supplier,sku).getFirst();
    }
    @Transactional
    public void removeRule(UUID tenant,UUID actor,UUID supplier,UUID sku) {
        if(supplier==null || sku==null)throw bad("Supplier and internal SKU are required");
        lockTenant(tenant);
        int changed=jdbc.update("""
            DELETE FROM erp_supplier_sku_rule
            WHERE tenant_id=? AND supplier_id=? AND product_sku_id=?
            """,tenant,supplier,sku);
        if(changed!=1)throw missing();
        auditRule(tenant,actor,supplier,sku,"RULE_REMOVED");
    }
    private void auditRule(UUID tenant,UUID actor,UUID supplier,UUID sku,String type) {
        jdbc.update("""
            INSERT INTO erp_supplier_sku_rule_audit
              (id,tenant_id,supplier_id,product_sku_id,actor_user_id,event_type)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,supplier,sku,actor,type);
    }

    private static final String SOURCE_SQL = """
        SELECT p.warehouse_id,r.supplier_id,sup.display_name AS supplier_name,
               p.product_sku_id,s.internal_sku,
               p.updated_at AS policy_updated_at,r.updated_at AS rule_updated_at,
               p.reorder_point,p.target_stock,r.minimum_order_quantity,r.pack_multiple,
               r.lead_time_days,r.currency_code,r.unit_cost,
               observed.on_hand,latest.source_movement_id
        FROM erp_local_stock_policy p
        JOIN erp_local_warehouse w
          ON w.tenant_id=p.tenant_id AND w.id=p.warehouse_id AND w.status='ACTIVE'
        JOIN product_sku s
          ON s.tenant_id=p.tenant_id AND s.id=p.product_sku_id AND s.status='ACTIVE'
        JOIN erp_supplier_sku_rule r
          ON r.tenant_id=p.tenant_id AND r.product_sku_id=p.product_sku_id
        JOIN erp_supplier sup
          ON sup.tenant_id=r.tenant_id AND sup.id=r.supplier_id AND sup.status='ACTIVE'
        LEFT JOIN LATERAL (
          SELECT SUM(m.quantity)::bigint AS on_hand
          FROM erp_local_stock_movement m
          WHERE m.tenant_id=p.tenant_id AND m.warehouse_id=p.warehouse_id
            AND m.product_sku_id=p.product_sku_id
        ) observed ON TRUE
        LEFT JOIN LATERAL (
          SELECT m.id AS source_movement_id
          FROM erp_local_stock_movement m
          WHERE m.tenant_id=p.tenant_id AND m.warehouse_id=p.warehouse_id
            AND m.product_sku_id=p.product_sku_id
          ORDER BY m.created_at DESC,m.id DESC LIMIT 1
        ) latest ON TRUE
        WHERE p.tenant_id=? AND p.warehouse_id=?
        """;
    private static Recommendation mapRecommendation(ResultSet rs,int row) throws SQLException {
        UUID evidence=rs.getObject("source_movement_id",UUID.class);
        Long onHand=evidence==null?null:rs.getLong("on_hand");
        int point=rs.getInt("reorder_point"),target=rs.getInt("target_stock");
        int moq=rs.getInt("minimum_order_quantity"),pack=rs.getInt("pack_multiple");
        String state=WarehouseStockAlertService.state(onHand,point);
        Long suggested=WarehouseStockAlertService.suggested(onHand,point,target);
        Long rounded=null;
        if(suggested!=null) {
            try{rounded=orderQuantity(suggested,moq,pack);}
            catch(ResponseStatusException e){state="LIMIT_EXCEEDED";}
        }
        return new Recommendation(rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("supplier_id",UUID.class).toString(),rs.getString("supplier_name"),
            rs.getObject("product_sku_id",UUID.class).toString(),rs.getString("internal_sku"),
            evidence==null?null:evidence.toString(),
            asInstant(rs,"policy_updated_at"),asInstant(rs,"rule_updated_at"),
            onHand,point,target,moq,pack,rs.getInt("lead_time_days"),
            rs.getString("currency_code").trim(),rs.getBigDecimal("unit_cost").toPlainString(),
            state,suggested,rounded);
    }
    @Transactional(readOnly=true)
    public List<Recommendation> recommendations(UUID tenant,UUID warehouse) {
        if(warehouse==null)throw bad("Warehouse is required");
        return jdbc.query(SOURCE_SQL+"""
            ORDER BY s.internal_sku,sup.display_name,r.supplier_id LIMIT 500
            """,SupplierPurchasePlanService::mapRecommendation,tenant,warehouse);
    }
    private Recommendation current(UUID tenant,UUID warehouse,UUID supplier,UUID sku) {
        return jdbc.query(SOURCE_SQL+"""
            AND p.product_sku_id=? AND r.supplier_id=?
            """,SupplierPurchasePlanService::mapRecommendation,tenant,warehouse,sku,supplier)
            .stream().findFirst().orElseThrow(SupplierPurchasePlanService::missing);
    }
    private static Instant instant(String raw) {
        if(raw==null)throw bad("Policy/rule version is required");
        try{return Instant.parse(raw);}catch(Exception e){throw bad("Invalid version timestamp");}
    }
    private static void validate(Request req) {
        if(req==null || req.requestKey()==null || req.warehouseId()==null
           || req.supplierId()==null || req.productSkuId()==null
           || req.expectedLastMovementId()==null || req.expectedOnHand()<0
           || req.expectedOrderQuantity()<1 || req.expectedOrderQuantity()>1_000_000)
            throw bad("Invalid purchase plan request");
        instant(req.expectedPolicyUpdatedAt());
        instant(req.expectedRuleUpdatedAt());
    }
    private static void verifyCurrent(Recommendation actual,UUID expectedEvidence,
                                      Instant expectedPolicy,Instant expectedRule,
                                      long expectedBalance,int expectedQty) {
        if(!"LOW_STOCK".equals(actual.state()) || actual.orderQuantity()==null
            || !java.util.Objects.equals(actual.sourceMovementId(),expectedEvidence.toString())
            || !instant(actual.policyUpdatedAt()).equals(expectedPolicy)
            || !instant(actual.ruleUpdatedAt()).equals(expectedRule)
            || !java.util.Objects.equals(actual.onHandQuantity(),expectedBalance)
            || actual.orderQuantity()!=expectedQty)
            throw conflict("Warehouse evidence, stock, policy or supplier terms changed; refresh");
    }

    private static Plan mapPlan(ResultSet rs,int i) throws SQLException {
        UUID order=rs.getObject("purchase_order_id",UUID.class);
        return new Plan(rs.getObject("id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("supplier_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getObject("source_movement_id",UUID.class).toString(),
            asInstant(rs,"policy_updated_at"),asInstant(rs,"rule_updated_at"),
            rs.getLong("on_hand_quantity"),rs.getInt("reorder_point"),
            rs.getInt("target_stock"),rs.getInt("suggested_quantity"),
            rs.getInt("minimum_order_quantity"),rs.getInt("pack_multiple"),
            rs.getInt("lead_time_days"),rs.getInt("ordered_quantity"),
            rs.getString("currency_code").trim(),rs.getBigDecimal("unit_cost").toPlainString(),
            rs.getObject("estimated_arrival_date",LocalDate.class).toString(),
            rs.getString("status"),order==null?null:order.toString(),
            rs.getObject("created_by",UUID.class).toString(),asInstant(rs,"created_at"));
    }
    private static final String PLAN_SELECT = """
        SELECT id,warehouse_id,supplier_id,product_sku_id,source_movement_id,
            policy_updated_at,rule_updated_at,on_hand_quantity,reorder_point,target_stock,
            suggested_quantity,minimum_order_quantity,pack_multiple,lead_time_days,
            ordered_quantity,currency_code,unit_cost,estimated_arrival_date,
            status,purchase_order_id,created_by,created_at
        FROM erp_local_purchase_plan WHERE tenant_id=?
        """;
    private Plan byId(UUID tenant,UUID id,boolean lock) {
        return jdbc.query(PLAN_SELECT+" AND id=?"+(lock?" FOR UPDATE":""),
            SupplierPurchasePlanService::mapPlan,tenant,id)
            .stream().findFirst().orElseThrow(SupplierPurchasePlanService::missing);
    }
    @Transactional(readOnly=true)
    public List<Plan> plans(UUID tenant,UUID warehouse) {
        if(warehouse==null)throw bad("Warehouse is required");
        return jdbc.query(PLAN_SELECT+"""
            AND warehouse_id=? ORDER BY created_at DESC,id DESC LIMIT 100
            """,SupplierPurchasePlanService::mapPlan,tenant,warehouse);
    }
    private static boolean replaySame(Plan prior,Request req) {
        return prior.warehouseId().equals(req.warehouseId().toString())
            && prior.supplierId().equals(req.supplierId().toString())
            && prior.productSkuId().equals(req.productSkuId().toString())
            && prior.sourceMovementId().equals(req.expectedLastMovementId().toString())
            && instant(prior.policyUpdatedAt()).equals(instant(req.expectedPolicyUpdatedAt()))
            && instant(prior.ruleUpdatedAt()).equals(instant(req.expectedRuleUpdatedAt()))
            && prior.onHandQuantity()==req.expectedOnHand()
            && prior.orderedQuantity()==req.expectedOrderQuantity();
    }
    /**
     * Local purchase lines have no destination warehouse yet. Blocking new
     * warehouse plans while any SKU purchase is open is safer than assuming
     * those units belong to a particular warehouse and subtracting them.
     */
    private void rejectOutstandingPurchases(UUID tenant,UUID sku,boolean includePlans) {
        Integer outstanding=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_purchase_order_line l
            JOIN erp_purchase_order o
              ON o.tenant_id=l.tenant_id AND o.id=l.purchase_order_id
            WHERE l.tenant_id=? AND l.product_sku_id=?
              AND o.status IN ('DRAFT','SUBMITTED','APPROVED','RECEIVING')
            """,Integer.class,tenant,sku);
        if(outstanding!=null && outstanding>0)
            throw conflict("Unfinished local purchase order already exists for this SKU");
        if(includePlans) {
            Integer plans=jdbc.queryForObject("""
                SELECT COUNT(*) FROM erp_local_purchase_plan
                WHERE tenant_id=? AND product_sku_id=? AND status='PROPOSED'
                """,Integer.class,tenant,sku);
            if(plans!=null && plans>0)
                throw conflict("An unconverted purchase plan exists for this SKU");
        }
    }
    private void auditPlan(UUID tenant,UUID actor,UUID id,String event) {
        jdbc.update("""
            INSERT INTO erp_local_purchase_plan_audit
              (id,tenant_id,plan_id,actor_user_id,event_type)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,event);
    }

    @Transactional
    public Plan propose(UUID tenant,UUID actor,Request request) {
        validate(request);
        lockTenant(tenant);
        List<UUID> priorIds=jdbc.queryForList("""
            SELECT id FROM erp_local_purchase_plan
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,request.requestKey());
        if(!priorIds.isEmpty()) {
            Plan previous=byId(tenant,priorIds.getFirst(),false);
            if(!previous.createdBy().equals(actor.toString()) || !replaySame(previous,request))
                throw conflict("Idempotency key was already used for another plan");
            return previous;
        }
        lockWarehouse(tenant,request.warehouseId());
        Recommendation source=current(tenant,request.warehouseId(),
            request.supplierId(),request.productSkuId());
        verifyCurrent(source,request.expectedLastMovementId(),
            instant(request.expectedPolicyUpdatedAt()),instant(request.expectedRuleUpdatedAt()),
            request.expectedOnHand(),request.expectedOrderQuantity());
        rejectOutstandingPurchases(tenant,request.productSkuId(),true);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_purchase_plan
              (id,tenant_id,request_key,warehouse_id,supplier_id,product_sku_id,
               source_movement_id,policy_updated_at,rule_updated_at,on_hand_quantity,
               reorder_point,target_stock,suggested_quantity,minimum_order_quantity,
               pack_multiple,lead_time_days,ordered_quantity,currency_code,unit_cost,
               estimated_arrival_date,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,request.requestKey(),request.warehouseId(),request.supplierId(),
            request.productSkuId(),request.expectedLastMovementId(),
            instant(source.policyUpdatedAt()),instant(source.ruleUpdatedAt()),
            request.expectedOnHand(),source.reorderPoint(),source.targetStock(),
            source.suggestedQuantity().intValue(),source.minimumOrderQuantity(),
            source.packMultiple(),source.leadTimeDays(),request.expectedOrderQuantity(),
            source.currency(),new BigDecimal(source.unitCost()),
            LocalDate.now(ZoneOffset.UTC).plusDays(source.leadTimeDays()),actor);
        auditPlan(tenant,actor,id,"PROPOSED");
        return byId(tenant,id,false);
    }

    @Transactional
    public Plan change(UUID tenant,UUID actor,UUID id,String action) {
        if(!"convert".equals(action) && !"cancel".equals(action))
            throw bad("Unsupported purchase plan action");
        lockTenant(tenant);
        Plan plan=byId(tenant,id,true);
        if("convert".equals(action) && "CONVERTED".equals(plan.status()))
            return plan; // repeat conversion never creates a second PO
        if(!"PROPOSED".equals(plan.status()))
            throw conflict("Purchase plan cannot be changed in this state");
        if("cancel".equals(action)) {
            jdbc.update("""
                UPDATE erp_local_purchase_plan SET status='CANCELLED',updated_at=now()
                WHERE tenant_id=? AND id=? AND status='PROPOSED'
                """,tenant,id);
            auditPlan(tenant,actor,id,"CANCELLED");
            return byId(tenant,id,false);
        }
        // Conversion is its own explicit human action. Never trust frozen evidence
        // if inventory, supplier or warehouse replenishment rules have changed.
        UUID warehouse=UUID.fromString(plan.warehouseId());
        lockWarehouse(tenant,warehouse);
        Recommendation source=current(tenant,warehouse,UUID.fromString(plan.supplierId()),
            UUID.fromString(plan.productSkuId()));
        verifyCurrent(source,UUID.fromString(plan.sourceMovementId()),
            instant(plan.policyUpdatedAt()),instant(plan.ruleUpdatedAt()),
            plan.onHandQuantity(),plan.orderedQuantity());
        rejectOutstandingPurchases(tenant,UUID.fromString(plan.productSkuId()),false);
        PurchasingService.Detail purchase=purchasing.create(tenant,actor,
            UUID.fromString(plan.supplierId()),plan.currency(),
            "Local warehouse plan "+id,
            List.of(new PurchasingService.RequestedLine(
                UUID.fromString(plan.productSkuId()),plan.orderedQuantity(),plan.unitCost())));
        jdbc.update("""
            UPDATE erp_local_purchase_plan
            SET status='CONVERTED',purchase_order_id=?,updated_at=now()
            WHERE tenant_id=? AND id=? AND status='PROPOSED'
            """,UUID.fromString(purchase.order().id()),tenant,id);
        auditPlan(tenant,actor,id,"CONVERTED");
        return byId(tenant,id,false);
    }
}
