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
 * Inspection is retrospective: V39 receipt posts all received units; rejected
 * units are quarantined by an equal and opposite signed ledger movement.
 * Vendor-return dispatch consumes quarantine, NEVER a second stock deduction.
 */
@Service
public class PurchaseQualityService {
    private final JdbcTemplate jdbc;
    public PurchaseQualityService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record InspectRequest(UUID requestKey,int acceptedQuantity,int rejectedQuantity,
                                 String reason) {}
    public record ReturnRequest(UUID requestKey,int quantity,String reference) {}
    public record Receipt(String id,String orderId,String lineId,String warehouseId,
                          String productSkuId,int receivedQuantity,String receivedAt,
                          String inspectionId,int acceptedQuantity,int rejectedQuantity,
                          long heldQuantity) {}
    public record Inspection(String id,String receiptId,String warehouseId,String productSkuId,
                             int inspectedQuantity,int acceptedQuantity,int rejectedQuantity,
                             String reason,String inspectedBy,String createdAt) {}
    public record SupplierReturn(String id,String inspectionId,String supplierId,int quantity,
                                 String reference,String status,String createdBy,
                                 String createdAt,String dispatchedAt) {}
    public record Finding(String id,String inspectionId,String receiptId,String ruleCode,
                          String state,String lastSeenAt) {}
    public record ScanResult(String id,String triggerType,int matchedCount,int clearedCount) {}
    public record FindingChange(String id,String state) {}
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);
    }
    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT,msg);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Local purchase QC record unavailable");
    }
    private static String when(ResultSet rs,String column) throws SQLException {
        OffsetDateTime d=rs.getObject(column,OffsetDateTime.class);
        return d==null?null:d.toInstant().toString();
    }
    static void checkParts(int total,int accepted,int rejected) {
        if(total<1 || accepted<0 || rejected<0 || ((long)accepted+rejected)!=total)
            throw bad("QC accepted plus rejected must equal actual receipt quantity");
    }
    private static String note(String raw) {
        if(raw==null || raw.strip().length()<5 || raw.strip().length()>300 ||
            raw.chars().anyMatch(Character::isISOControl))
            throw bad("QC reason must be 5..300 printable characters");
        return raw.strip();
    }
    private static String reference(String raw) {
        if(raw==null || raw.isBlank())return null;
        if(raw.strip().length()>120 || raw.chars().anyMatch(Character::isISOControl))
            throw bad("Supplier return reference must be at most 120 printable characters");
        return raw.strip();
    }
    private void lockTenant(UUID tenant) {
        List<UUID> found=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(found.size()!=1)throw missing();
    }
    private static Inspection mapInspection(ResultSet rs,int i) throws SQLException {
        return new Inspection(rs.getObject("id",UUID.class).toString(),
            rs.getObject("purchase_receipt_id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getInt("inspected_quantity"),rs.getInt("accepted_quantity"),
            rs.getInt("rejected_quantity"),rs.getString("reason"),
            rs.getObject("inspected_by",UUID.class).toString(),when(rs,"created_at"));
    }
    private static final String INSPECT_SELECT = """
        SELECT id,purchase_receipt_id,warehouse_id,product_sku_id,
          inspected_quantity,accepted_quantity,rejected_quantity,reason,
          inspected_by,created_at FROM erp_purchase_receipt_qc WHERE tenant_id=?
        """;
    private Inspection inspection(UUID tenant,UUID id) {
        return jdbc.query(INSPECT_SELECT+" AND id=?",
            PurchaseQualityService::mapInspection,tenant,id).stream().findFirst()
            .orElseThrow(PurchaseQualityService::missing);
    }
    private static Receipt mapReceipt(ResultSet rs,int i) throws SQLException {
        UUID warehouse=rs.getObject("warehouse_id",UUID.class);
        UUID inspection=rs.getObject("inspection_id",UUID.class);
        return new Receipt(rs.getObject("id",UUID.class).toString(),
            rs.getObject("purchase_order_id",UUID.class).toString(),
            rs.getObject("purchase_order_line_id",UUID.class).toString(),
            warehouse==null?null:warehouse.toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getInt("quantity"),when(rs,"received_at"),
            inspection==null?null:inspection.toString(),rs.getInt("accepted_quantity"),
            rs.getInt("rejected_quantity"),rs.getLong("held_quantity"));
    }
    private static final String RECEIPT_SELECT = """
        SELECT r.id,r.purchase_order_id,r.purchase_order_line_id,r.warehouse_id,
          l.product_sku_id,r.quantity,r.received_at,q.id AS inspection_id,
          COALESCE(q.accepted_quantity,0)::integer AS accepted_quantity,
          COALESCE(q.rejected_quantity,0)::integer AS rejected_quantity,
          (COALESCE(q.rejected_quantity,0) -
            COALESCE((SELECT SUM(v.quantity) FROM erp_supplier_return v
              WHERE v.tenant_id=q.tenant_id AND v.inspection_id=q.id
                AND v.status='DISPATCHED'),0))::bigint AS held_quantity
        FROM erp_purchase_receipt r
        JOIN erp_purchase_order_line l
          ON l.tenant_id=r.tenant_id AND l.id=r.purchase_order_line_id
        LEFT JOIN erp_purchase_receipt_qc q
          ON q.tenant_id=r.tenant_id AND q.purchase_receipt_id=r.id
        WHERE r.tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Receipt> receipts(UUID tenant,UUID order) {
        return jdbc.query(RECEIPT_SELECT+"""
            AND r.purchase_order_id=?
            ORDER BY r.received_at DESC,r.id DESC LIMIT 300
            """,PurchaseQualityService::mapReceipt,tenant,order);
    }
    private Receipt receipt(UUID tenant,UUID receiptId) {
        return jdbc.query(RECEIPT_SELECT+" AND r.id=?",
            PurchaseQualityService::mapReceipt,tenant,receiptId)
            .stream().findFirst().orElseThrow(PurchaseQualityService::missing);
    }
    private void event(UUID tenant,UUID actor,UUID qc,UUID returnId,
                       String type,int quantity,String remark) {
        jdbc.update("""
            INSERT INTO erp_purchase_quality_event
              (id,tenant_id,inspection_id,return_id,event_type,actor_user_id,quantity,note)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,qc,returnId,type,actor,quantity,remark);
    }

    @Transactional
    public Inspection inspect(UUID tenant,UUID actor,UUID receiptId,InspectRequest request) {
        if(request==null || request.requestKey()==null || receiptId==null)
            throw bad("Receipt and idempotency key required");
        String reason=note(request.reason());
        lockTenant(tenant);
        List<UUID> prior=jdbc.queryForList("""
            SELECT id FROM erp_purchase_receipt_qc
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,request.requestKey());
        if(!prior.isEmpty()) {
            Inspection saved=inspection(tenant,prior.getFirst());
            if(!saved.receiptId().equals(receiptId.toString())
                || saved.acceptedQuantity()!=request.acceptedQuantity()
                || saved.rejectedQuantity()!=request.rejectedQuantity()
                || !saved.reason().equals(reason)
                || !saved.inspectedBy().equals(actor.toString()))
                throw conflict("QC request key already used with different contents");
            return saved;
        }
        Receipt source=receipt(tenant,receiptId);
        checkParts(source.receivedQuantity(),request.acceptedQuantity(),
            request.rejectedQuantity());
        if(source.inspectionId()!=null)
            throw conflict("This physical receipt was already inspected");
        if(source.warehouseId()==null)
            throw conflict("Legacy receipt has no known local warehouse to quarantine");
        UUID warehouse=UUID.fromString(source.warehouseId());
        // Coordinates with outbound, warehouse transfer and later receipts.
        List<UUID> locked=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse WHERE tenant_id=? AND id=?
            FOR UPDATE
            """,UUID.class,tenant,warehouse);
        if(locked.size()!=1)throw missing();
        long available=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_local_stock_movement
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,UUID.fromString(source.productSkuId()));
        if(available<request.rejectedQuantity())
            throw conflict("Recorded local stock is insufficient to quarantine rejected units");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_purchase_receipt_qc
              (id,tenant_id,request_key,purchase_receipt_id,warehouse_id,
               product_sku_id,inspected_quantity,accepted_quantity,rejected_quantity,
               reason,inspected_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,request.requestKey(),receiptId,warehouse,
            UUID.fromString(source.productSkuId()),source.receivedQuantity(),
            request.acceptedQuantity(),request.rejectedQuantity(),reason,actor);
        if(request.rejectedQuantity()>0)jdbc.update("""
            INSERT INTO erp_local_stock_movement
              (id,tenant_id,warehouse_id,product_sku_id,inspection_id,
               movement_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,'QC_QUARANTINE',?,?)
            """,UUID.randomUUID(),tenant,warehouse,
            UUID.fromString(source.productSkuId()),id,-request.rejectedQuantity(),actor);
        event(tenant,actor,id,null,"INSPECTED",request.rejectedQuantity(),reason);
        return inspection(tenant,id);
    }

    private static SupplierReturn mapReturn(ResultSet rs,int i) throws SQLException {
        return new SupplierReturn(rs.getObject("id",UUID.class).toString(),
            rs.getObject("inspection_id",UUID.class).toString(),
            rs.getObject("supplier_id",UUID.class).toString(),
            rs.getInt("quantity"),rs.getString("reference"),rs.getString("status"),
            rs.getObject("created_by",UUID.class).toString(),when(rs,"created_at"),
            when(rs,"dispatched_at"));
    }
    private static final String RETURN_SELECT = """
        SELECT id,inspection_id,supplier_id,quantity,reference,status,created_by,
          created_at,dispatched_at FROM erp_supplier_return WHERE tenant_id=?
        """;
    private SupplierReturn byReturnId(UUID tenant,UUID id) {
        return jdbc.query(RETURN_SELECT+" AND id=?",
            PurchaseQualityService::mapReturn,tenant,id).stream().findFirst()
            .orElseThrow(PurchaseQualityService::missing);
    }
    @Transactional(readOnly=true)
    public List<SupplierReturn> returns(UUID tenant,UUID inspectionId) {
        return jdbc.query(RETURN_SELECT+"""
            AND inspection_id=? ORDER BY created_at DESC,id DESC LIMIT 100
            """,PurchaseQualityService::mapReturn,tenant,inspectionId);
    }
    @Transactional
    public SupplierReturn createReturn(UUID tenant,UUID actor,UUID qcId,ReturnRequest request) {
        if(qcId==null || request==null || request.requestKey()==null
            || request.quantity()<1 || request.quantity()>1_000_000)
            throw bad("Invalid supplier return request");
        String reference=reference(request.reference());
        lockTenant(tenant);
        List<UUID> prior=jdbc.queryForList("""
            SELECT id FROM erp_supplier_return WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,request.requestKey());
        if(!prior.isEmpty()) {
            SupplierReturn saved=byReturnId(tenant,prior.getFirst());
            if(!saved.inspectionId().equals(qcId.toString())
                || saved.quantity()!=request.quantity()
                || !java.util.Objects.equals(saved.reference(),reference)
                || !saved.createdBy().equals(actor.toString()))
                throw conflict("Supplier return idempotency key already used");
            return saved;
        }
        List<UUID> found=jdbc.queryForList("""
            SELECT id FROM erp_purchase_receipt_qc
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,qcId);
        if(found.size()!=1)throw missing();
        Inspection source=inspection(tenant,qcId);
        if(source.rejectedQuantity()==0)
            throw conflict("No quarantined rejected units for this receipt");
        Long allocated=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_supplier_return
            WHERE tenant_id=? AND inspection_id=? AND status<>'CANCELLED'
            """,Long.class,tenant,qcId);
        if((allocated==null?0:allocated)+request.quantity()>source.rejectedQuantity())
            throw conflict("Return quantity exceeds unallocated rejected QC units");
        List<UUID> supplier=jdbc.queryForList("""
            SELECT o.supplier_id
            FROM erp_purchase_receipt r
            JOIN erp_purchase_order o
              ON o.tenant_id=r.tenant_id AND o.id=r.purchase_order_id
            WHERE r.tenant_id=? AND r.id=?
            """,UUID.class,tenant,UUID.fromString(source.receiptId()));
        if(supplier.size()!=1)throw missing();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_supplier_return
              (id,tenant_id,request_key,inspection_id,supplier_id,quantity,
               reference,created_by)
            VALUES (?,?,?,?,?,?,?,?)
            """,id,tenant,request.requestKey(),qcId,supplier.getFirst(),
            request.quantity(),reference,actor);
        event(tenant,actor,qcId,id,"RETURN_DRAFTED",request.quantity(),reference);
        return byReturnId(tenant,id);
    }
    @Transactional
    public SupplierReturn changeReturn(UUID tenant,UUID actor,UUID id,String action) {
        if(!"dispatch".equals(action) && !"cancel".equals(action))
            throw bad("Unsupported supplier return action");
        lockTenant(tenant);
        SupplierReturn original=byReturnId(tenant,id);
        List<UUID> locked=jdbc.queryForList("""
            SELECT id FROM erp_purchase_receipt_qc
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,UUID.fromString(original.inspectionId()));
        if(locked.size()!=1)throw missing();
        SupplierReturn latest=byReturnId(tenant,id);
        if("DISPATCHED".equals(latest.status()) && "dispatch".equals(action))
            return latest;
        if("CANCELLED".equals(latest.status()) && "cancel".equals(action))
            return latest;
        if(!"DRAFT".equals(latest.status()))
            throw conflict("Only unshipped supplier return drafts can change state");
        String status="dispatch".equals(action)?"DISPATCHED":"CANCELLED";
        jdbc.update("""
            UPDATE erp_supplier_return SET status=?,updated_at=now(),
              dispatched_at=CASE WHEN ?='DISPATCHED' THEN now() ELSE NULL END
            WHERE tenant_id=? AND id=? AND status='DRAFT'
            """,status,status,tenant,id);
        event(tenant,actor,UUID.fromString(latest.inspectionId()),id,
            "dispatch".equals(action)?"RETURN_DISPATCHED":"RETURN_CANCELLED",
            latest.quantity(),latest.reference());
        // Rejected goods were removed from on-hand at inspection. NO stock movement here.
        return byReturnId(tenant,id);
    }

    private static Finding mapFinding(ResultSet rs,int i) throws SQLException {
        return new Finding(rs.getObject("id",UUID.class).toString(),
            rs.getObject("inspection_id",UUID.class).toString(),
            rs.getObject("purchase_receipt_id",UUID.class).toString(),
            rs.getString("rule_code"),rs.getString("state"),when(rs,"last_seen_at"));
    }
    @Transactional(readOnly=true)
    public List<Finding> findings(UUID tenant,String filter) {
        if(!"ACTIVE".equals(filter) && !"ALL".equals(filter))
            throw bad("Invalid QC finding filter");
        return jdbc.query("""
            SELECT f.id,f.inspection_id,q.purchase_receipt_id,
              f.rule_code,f.state,f.last_seen_at
            FROM erp_purchase_quality_finding f
            JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=f.tenant_id AND q.id=f.inspection_id
            WHERE f.tenant_id=? AND (?='ALL' OR f.state IN ('OPEN','ACKNOWLEDGED'))
            ORDER BY f.changed_at DESC,f.id DESC LIMIT 100
            """,PurchaseQualityService::mapFinding,tenant,filter);
    }
    private static final String UPSERT = """
        ON CONFLICT(tenant_id,inspection_id,rule_code)
        DO UPDATE SET last_scan_id=EXCLUDED.last_scan_id,last_seen_at=now(),
          state=CASE WHEN erp_purchase_quality_finding.state='CLEARED' THEN 'OPEN'
                ELSE erp_purchase_quality_finding.state END,
          changed_at=CASE WHEN erp_purchase_quality_finding.state='CLEARED' THEN now()
                ELSE erp_purchase_quality_finding.changed_at END
        """;
    private static final String DUE = """
        FROM erp_purchase_receipt_qc q
        WHERE q.tenant_id=? AND q.rejected_quantity>
          (SELECT COALESCE(SUM(v.quantity),0) FROM erp_supplier_return v
            WHERE v.tenant_id=q.tenant_id AND v.inspection_id=q.id
              AND v.status='DISPATCHED')
        """;
    private static final String REJECTED = """
        INSERT INTO erp_purchase_quality_finding
          (id,tenant_id,inspection_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),q.tenant_id,q.id,'QC_REJECTED_PENDING',?
        """+DUE+UPSERT;
    private static final String AGED = """
        INSERT INTO erp_purchase_quality_finding
          (id,tenant_id,inspection_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),q.tenant_id,q.id,'QC_HOLD_AGED',?
        """+DUE+"""
         AND q.created_at<now()-interval '7 days'
        """+UPSERT;

    @Transactional
    public ScanResult scan(UUID tenant,UUID actor,String trigger) {
        if(!"MANUAL".equals(trigger) && !"SCHEDULED".equals(trigger))
            throw bad("Invalid QC scan trigger");
        lockTenant(tenant);
        UUID id=UUID.randomUUID();
        int found=jdbc.update(REJECTED,id,tenant);
        found+=jdbc.update(AGED,id,tenant);
        int cleared=jdbc.update("""
            UPDATE erp_purchase_quality_finding
            SET state='CLEARED',changed_at=now()
            WHERE tenant_id=? AND last_scan_id<>? AND state<>'CLEARED'
            """,tenant,id);
        jdbc.update("""
            INSERT INTO erp_purchase_quality_scan_run
              (id,tenant_id,actor_user_id,trigger_type,matched_count,cleared_count)
            VALUES (?,?,?,?,?,?)
            """,id,tenant,actor,trigger,found,cleared);
        return new ScanResult(id.toString(),trigger,found,cleared);
    }
    @Transactional
    public FindingChange changeFinding(UUID tenant,UUID actor,UUID id,String state) {
        if(state==null || !List.of("OPEN","ACKNOWLEDGED","DONE").contains(state))
            throw bad("Invalid QC finding state");
        lockTenant(tenant);
        List<String> found=jdbc.query("""
            SELECT state FROM erp_purchase_quality_finding
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getString(1),tenant,id);
        if(found.size()!=1)throw missing();
        String old=found.getFirst();
        if("CLEARED".equals(old))throw conflict("QC finding already cleared");
        if(!old.equals(state)) {
            jdbc.update("""
                UPDATE erp_purchase_quality_finding
                SET state=?,changed_at=now() WHERE tenant_id=? AND id=?
                """,state,tenant,id);
            jdbc.update("""
                INSERT INTO erp_purchase_quality_finding_audit
                  (id,tenant_id,finding_id,actor_user_id,previous_state,new_state)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,id,actor,old,state);
        }
        return new FindingChange(id.toString(),state);
    }
}
