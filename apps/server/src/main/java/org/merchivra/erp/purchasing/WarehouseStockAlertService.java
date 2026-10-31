package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-scoped advisory LOCAL warehouse rules. No FBA/FBM synchronization,
 * purchasing side effects, or optimistic substitution of unknown stock with zero.
 */
@Service
public class WarehouseStockAlertService {
    private final JdbcTemplate jdbc;
    public WarehouseStockAlertService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Policy(String warehouseId,String productSkuId,int reorderPoint,
                         int targetStock,String updatedAt) {}
    public record Advice(Policy policy,String internalSku,Long onHandQuantity,
                         String state,Long suggestedQuantity,
                         long approvedUnreceivedQuantity) {}
    public record Finding(String id,String warehouseId,String productSkuId,String internalSku,
                          String ruleCode,String state,String lastSeenAt) {}
    public record ScanResult(String id,String triggerType,int matchedCount,int clearedCount) {}
    public record FindingChange(String id,String state) {}

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Warehouse, SKU or finding unavailable");
    }
    private static String timestamp(ResultSet rs,String column) throws SQLException {
        return rs.getObject(column,OffsetDateTime.class).toInstant().toString();
    }
    static String state(Long onHand,int reorderPoint) {
        if(onHand==null)return "NO_LEDGER";
        if(onHand<0)return "LEDGER_ANOMALY";
        return onHand<=reorderPoint?"LOW_STOCK":"HEALTHY";
    }
    static Long suggested(Long onHand,int reorderPoint,int targetStock) {
        return "LOW_STOCK".equals(state(onHand,reorderPoint))
            ? Math.max(0L,(long)targetStock-onHand) : null;
    }
    private static Policy mapPolicy(ResultSet rs,int row) throws SQLException {
        return new Policy(rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getInt("reorder_point"),rs.getInt("target_stock"),
            timestamp(rs,"updated_at"));
    }
    private void requireWarehouse(UUID tenant,UUID warehouse) {
        if(warehouse==null)throw bad("Warehouse required");
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,Integer.class,tenant,warehouse);
        if(count==null || count!=1)throw missing();
    }
    private void auditPolicy(UUID tenant,UUID actor,UUID warehouse,UUID sku,String action) {
        jdbc.update("""
            INSERT INTO erp_local_stock_policy_audit
              (id,tenant_id,warehouse_id,product_sku_id,actor_user_id,action)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,warehouse,sku,actor,action);
    }

    @Transactional
    public Policy setPolicy(UUID tenant,UUID actor,UUID warehouse,UUID sku,
                            int reorderPoint,int targetStock) {
        if(sku==null || reorderPoint<0 || reorderPoint>10_000_000
           || targetStock<=reorderPoint || targetStock>10_000_000)
            throw bad("Invalid warehouse replenishment policy");
        List<UUID> warehouses=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR SHARE
            """,UUID.class,tenant,warehouse);
        if(warehouses.size()!=1)throw missing();
        List<UUID> skus=jdbc.queryForList("""
            SELECT id FROM product_sku WHERE tenant_id=? AND id=? AND status='ACTIVE'
            FOR SHARE
            """,UUID.class,tenant,sku);
        if(skus.size()!=1)throw missing();
        jdbc.update("""
            INSERT INTO erp_local_stock_policy
              (tenant_id,warehouse_id,product_sku_id,reorder_point,target_stock,updated_by)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (tenant_id,warehouse_id,product_sku_id) DO UPDATE SET
              reorder_point=EXCLUDED.reorder_point,target_stock=EXCLUDED.target_stock,
              updated_by=EXCLUDED.updated_by,updated_at=now()
            """,tenant,warehouse,sku,reorderPoint,targetStock,actor);
        auditPolicy(tenant,actor,warehouse,sku,"POLICY_SET");
        return jdbc.query("""
            SELECT warehouse_id,product_sku_id,reorder_point,target_stock,updated_at
            FROM erp_local_stock_policy
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,WarehouseStockAlertService::mapPolicy,tenant,warehouse,sku).getFirst();
    }
    @Transactional
    public void removePolicy(UUID tenant,UUID actor,UUID warehouse,UUID sku) {
        if(sku==null)throw bad("SKU required");
        // Match plan creation/conversion warehouse locks so policy removal cannot
        // race an evidence snapshot between source verification and PO creation.
        List<UUID> warehouses=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR SHARE
            """,UUID.class,tenant,warehouse);
        if(warehouses.size()!=1)throw missing();
        int changed=jdbc.update("""
            DELETE FROM erp_local_stock_policy
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,tenant,warehouse,sku);
        if(changed!=1)throw missing();
        auditPolicy(tenant,actor,warehouse,sku,"POLICY_REMOVED");
    }

    @Transactional(readOnly=true)
    public List<Advice> advice(UUID tenant,UUID warehouse) {
        requireWarehouse(tenant,warehouse);
        // An empty ledger has NULL on-hand, deliberately distinct from observed zero.
        // Approved unreceived orders are not warehouse-assigned: show them for review,
        // but NEVER subtract them from a particular warehouse's suggestion.
        return jdbc.query("""
            SELECT p.warehouse_id,p.product_sku_id,p.reorder_point,p.target_stock,
                p.updated_at,s.internal_sku,b.on_hand,b.event_count,
                COALESCE(po.pending_quantity,0)::bigint AS approved_pending
            FROM erp_local_stock_policy p
            JOIN product_sku s ON s.tenant_id=p.tenant_id AND s.id=p.product_sku_id
            LEFT JOIN LATERAL (
              SELECT COUNT(*)::bigint AS event_count,SUM(m.quantity)::bigint AS on_hand
              FROM erp_local_stock_movement m
              WHERE m.tenant_id=p.tenant_id AND m.warehouse_id=p.warehouse_id
                AND m.product_sku_id=p.product_sku_id
            ) b ON TRUE
            LEFT JOIN LATERAL (
              SELECT SUM(l.ordered_quantity-COALESCE(r.received_units,0))::bigint
                AS pending_quantity
              FROM erp_purchase_order_line l
              JOIN erp_purchase_order o ON o.tenant_id=l.tenant_id
                AND o.id=l.purchase_order_id
              LEFT JOIN LATERAL (
                SELECT SUM(pr.quantity)::bigint AS received_units
                FROM erp_purchase_receipt pr
                WHERE pr.tenant_id=l.tenant_id
                  AND pr.purchase_order_id=l.purchase_order_id
                  AND pr.purchase_order_line_id=l.id
              ) r ON TRUE
              WHERE l.tenant_id=p.tenant_id AND l.product_sku_id=p.product_sku_id
                AND o.status IN ('APPROVED','RECEIVING')
            ) po ON TRUE
            WHERE p.tenant_id=? AND p.warehouse_id=?
            ORDER BY s.internal_sku LIMIT 500
            """,(rs,i)->{
                Policy policy=mapPolicy(rs,i);
                Long units=rs.getLong("event_count")>0 ? rs.getLong("on_hand") : null;
                return new Advice(policy,rs.getString("internal_sku"),
                    units,state(units,policy.reorderPoint()),
                    suggested(units,policy.reorderPoint(),policy.targetStock()),
                    rs.getLong("approved_pending"));
            },tenant,warehouse);
    }

    private static Finding mapFinding(ResultSet rs,int row) throws SQLException {
        return new Finding(rs.getObject("id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getString("internal_sku"),rs.getString("rule_code"),
            rs.getString("state"),timestamp(rs,"last_seen_at"));
    }
    @Transactional(readOnly=true)
    public List<Finding> findings(UUID tenant,UUID warehouse,String filter) {
        if(!"ACTIVE".equals(filter) && !"ALL".equals(filter))
            throw bad("filter must be ACTIVE or ALL");
        requireWarehouse(tenant,warehouse);
        return jdbc.query("""
            SELECT f.id,f.warehouse_id,f.product_sku_id,s.internal_sku,
                f.rule_code,f.state,f.last_seen_at
            FROM erp_local_stock_finding f
            JOIN product_sku s ON s.tenant_id=f.tenant_id AND s.id=f.product_sku_id
            WHERE f.tenant_id=? AND f.warehouse_id=?
              AND (?='ALL' OR f.state IN ('OPEN','ACKNOWLEDGED'))
            ORDER BY CASE f.state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                  WHEN 'DONE' THEN 2 ELSE 3 END,
                f.changed_at DESC,f.id DESC LIMIT 100
            """,WarehouseStockAlertService::mapFinding,tenant,warehouse,filter);
    }

    private static final String UPSERT = """
        ON CONFLICT (tenant_id,warehouse_id,product_sku_id,rule_code)
        DO UPDATE SET last_scan_id=EXCLUDED.last_scan_id,last_seen_at=now(),
          state=CASE WHEN erp_local_stock_finding.state='CLEARED' THEN 'OPEN'
              ELSE erp_local_stock_finding.state END,
          changed_at=CASE WHEN erp_local_stock_finding.state='CLEARED'
              THEN now() ELSE erp_local_stock_finding.changed_at END
        """;

    private static final String LOW_SQL = """
        INSERT INTO erp_local_stock_finding
          (id,tenant_id,warehouse_id,product_sku_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),p.tenant_id,p.warehouse_id,p.product_sku_id,
          'LOCAL_LOW_STOCK',?
        FROM erp_local_stock_policy p
        JOIN erp_local_warehouse w ON w.tenant_id=p.tenant_id
          AND w.id=p.warehouse_id AND w.status='ACTIVE'
        JOIN erp_local_stock_movement m ON m.tenant_id=p.tenant_id
          AND m.warehouse_id=p.warehouse_id AND m.product_sku_id=p.product_sku_id
        WHERE p.tenant_id=?
        GROUP BY p.tenant_id,p.warehouse_id,p.product_sku_id,p.reorder_point
        HAVING SUM(m.quantity) BETWEEN 0 AND p.reorder_point
        """+UPSERT;

    private static final String NEGATIVE_SQL = """
        INSERT INTO erp_local_stock_finding
          (id,tenant_id,warehouse_id,product_sku_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),m.tenant_id,m.warehouse_id,m.product_sku_id,
          'LOCAL_LEDGER_NEGATIVE',?
        FROM erp_local_stock_movement m
        JOIN erp_local_warehouse w ON w.tenant_id=m.tenant_id
          AND w.id=m.warehouse_id AND w.status='ACTIVE'
        WHERE m.tenant_id=?
        GROUP BY m.tenant_id,m.warehouse_id,m.product_sku_id
        HAVING SUM(m.quantity)<0
        """+UPSERT;

    private static final String COUNT_SQL = """
        INSERT INTO erp_local_stock_finding
          (id,tenant_id,warehouse_id,product_sku_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),o.tenant_id,o.source_warehouse_id,o.product_sku_id,
          'LOCAL_COUNT_VARIANCE',?
        FROM erp_local_stock_operation o
        JOIN erp_local_warehouse w ON w.tenant_id=o.tenant_id
          AND w.id=o.source_warehouse_id AND w.status='ACTIVE'
        WHERE o.tenant_id=? AND o.operation_type='COUNT'
          AND abs(o.applied_delta)>=100
          AND o.created_at>=now()-interval '7 days'
        GROUP BY o.tenant_id,o.source_warehouse_id,o.product_sku_id
        """+UPSERT;

    @Transactional
    public ScanResult scan(UUID tenant,UUID actor,String trigger) {
        if(!"MANUAL".equals(trigger) && !"SCHEDULED".equals(trigger))
            throw bad("Invalid warehouse scan trigger");
        List<UUID> active=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(active.size()!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tenant inactive");
        UUID id=UUID.randomUUID();
        int matched=jdbc.update(LOW_SQL,id,tenant);
        matched+=jdbc.update(NEGATIVE_SQL,id,tenant);
        matched+=jdbc.update(COUNT_SQL,id,tenant);
        int cleared=jdbc.update("""
            UPDATE erp_local_stock_finding SET state='CLEARED',changed_at=now()
            WHERE tenant_id=? AND last_scan_id<>? AND state<>'CLEARED'
            """,tenant,id);
        jdbc.update("""
            INSERT INTO erp_local_stock_scan_run
              (id,tenant_id,actor_user_id,trigger_type,matched_count,cleared_count)
            VALUES (?,?,?,?,?,?)
            """,id,tenant,actor,trigger,matched,cleared);
        return new ScanResult(id.toString(),trigger,matched,cleared);
    }

    @Transactional
    public FindingChange changeState(UUID tenant,UUID actor,UUID finding,String requested) {
        if(requested==null || !List.of("OPEN","ACKNOWLEDGED","DONE").contains(requested))
            throw bad("Invalid warehouse finding state");
        List<String> states=jdbc.query("""
            SELECT state FROM erp_local_stock_finding
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getString(1),tenant,finding);
        if(states.size()!=1)throw missing();
        String old=states.getFirst();
        if("CLEARED".equals(old))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Finding cleared");
        if(!old.equals(requested)) {
            jdbc.update("""
                UPDATE erp_local_stock_finding SET state=?,changed_at=now()
                WHERE tenant_id=? AND id=?
                """,requested,tenant,finding);
            jdbc.update("""
                INSERT INTO erp_local_stock_finding_audit
                  (id,tenant_id,finding_id,actor_user_id,old_state,new_state)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,finding,actor,old,requested);
        }
        return new FindingChange(finding.toString(),requested);
    }
}
