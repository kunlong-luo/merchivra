package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit locally entered supplier advance shipment notices. SHIPPED only means
 * an Owner recorded a supplier's shipment claim; only receipt rows prove local arrival.
 */
@Service
public class SupplierAsnService {
    private final JdbcTemplate jdbc;
    public SupplierAsnService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Create(UUID requestKey,UUID lineId,UUID warehouseId,int quantity,
                         LocalDate expectedArrivalDate,String trackingReference) {}
    public record ChangeEta(LocalDate expectedArrivalDate,LocalDate expectedPreviousEta,
                            String reason) {}
    public record Asn(String id,String orderId,String lineId,String warehouseId,
                      int quantity,long receivedQuantity,String expectedArrivalDate,
                      String trackingReference,String status,String dispatchReportedAt,
                      String createdAt) {}
    public record Event(String id,String eventType,String actorUserId,Integer quantity,
                        String oldEta,String newEta,String note,String createdAt) {}
    public record Finding(String id,String asnId,String orderId,String trackingReference,
                          String ruleCode,String state,String expectedArrivalDate,
                          String lastSeenAt) {}
    public record ScanResult(String id,String triggerType,int matchedCount,int clearedCount) {}
    public record FindingChange(String id,String state) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Supplier ASN or purchase order unavailable");
    }
    static void validateEta(LocalDate eta) {
        LocalDate today=LocalDate.now(java.time.ZoneOffset.UTC);
        if(eta==null || eta.isBefore(today.minusDays(365))
           || eta.isAfter(today.plusDays(730)))
            throw bad("ASN expected arrival must be within the allowed planning horizon");
    }
    private static String reference(String value) {
        if(value==null || value.isBlank())return null;
        String stripped=value.strip();
        if(stripped.length()>120 ||
           stripped.chars().anyMatch(Character::isISOControl))
            throw bad("Shipment reference must be at most 120 printable characters");
        return stripped;
    }
    private static String reason(String value) {
        if(value==null || value.strip().length()<5 || value.strip().length()>300 ||
           value.chars().anyMatch(Character::isISOControl))
            throw bad("ETA change reason must be 5..300 printable characters");
        return value.strip();
    }
    private static String instant(ResultSet rs,String field) throws SQLException {
        OffsetDateTime value=rs.getObject(field,OffsetDateTime.class);
        return value==null?null:value.toInstant().toString();
    }
    private static Asn asn(ResultSet rs,int i) throws SQLException {
        return new Asn(rs.getObject("id",UUID.class).toString(),
            rs.getObject("purchase_order_id",UUID.class).toString(),
            rs.getObject("purchase_order_line_id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getInt("quantity"),rs.getLong("received_units"),
            rs.getObject("expected_arrival_date",LocalDate.class).toString(),
            rs.getString("tracking_reference"),rs.getString("status"),
            instant(rs,"dispatch_reported_at"),instant(rs,"created_at"));
    }
    private static final String ASN_SQL = """
        SELECT a.id,a.purchase_order_id,a.purchase_order_line_id,a.warehouse_id,
          a.quantity,a.expected_arrival_date,a.tracking_reference,a.status,
          a.dispatch_reported_at,a.created_at,
          COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
            WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id),0)::bigint AS received_units
        FROM erp_purchase_asn a WHERE a.tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Asn> list(UUID tenant,UUID order) {
        return jdbc.query(ASN_SQL+"""
            AND a.purchase_order_id=?
            ORDER BY a.created_at DESC,a.id DESC LIMIT 200
            """,SupplierAsnService::asn,tenant,order);
    }
    private Asn get(UUID tenant,UUID id) {
        return jdbc.query(ASN_SQL+" AND a.id=?",SupplierAsnService::asn,tenant,id)
            .stream().findFirst().orElseThrow(SupplierAsnService::missing);
    }
    @Transactional(readOnly=true)
    public List<Event> events(UUID tenant,UUID order,UUID asnId) {
        if(tenant==null || order==null || asnId==null)throw bad("Order and ASN required");
        List<UUID> existing=jdbc.queryForList("""
            SELECT id FROM erp_purchase_asn
            WHERE tenant_id=? AND purchase_order_id=? AND id=?
            """,UUID.class,tenant,order,asnId);
        if(existing.size()!=1)throw missing();
        return jdbc.query("""
            SELECT e.id,e.event_type,e.actor_user_id,e.quantity,
              e.old_eta,e.new_eta,e.note,e.created_at
            FROM erp_purchase_asn_event e
            WHERE e.tenant_id=? AND e.asn_id=?
            ORDER BY e.created_at DESC,e.id DESC LIMIT 200
            """,(rs,i)->{
                int count=rs.getInt("quantity");
                boolean quantityMissing=rs.wasNull();
                return new Event(rs.getObject("id",UUID.class).toString(),
                    rs.getString("event_type"),
                    rs.getObject("actor_user_id",UUID.class).toString(),
                    quantityMissing?null:count,
                    rs.getObject("old_eta",LocalDate.class)==null?null:
                      rs.getObject("old_eta",LocalDate.class).toString(),
                    rs.getObject("new_eta",LocalDate.class)==null?null:
                      rs.getObject("new_eta",LocalDate.class).toString(),
                    rs.getString("note"),instant(rs,"created_at"));
            },tenant,asnId);
    }
    private void activeOrder(UUID tenant,UUID order) {
        List<UUID> ids=jdbc.queryForList("""
            SELECT id FROM erp_purchase_order
            WHERE tenant_id=? AND id=? AND status IN ('APPROVED','RECEIVING')
            FOR UPDATE
            """,UUID.class,tenant,order);
        if(ids.size()!=1)throw conflict("Only approved or receiving POs permit supplier ASN changes");
    }
    private void event(UUID tenant,UUID actor,UUID asn,String action,Integer quantity,
                       LocalDate oldEta,LocalDate newEta,String note) {
        jdbc.update("""
            INSERT INTO erp_purchase_asn_event
              (id,tenant_id,asn_id,event_type,actor_user_id,quantity,old_eta,new_eta,note)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,asn,action,actor,quantity,oldEta,newEta,note);
    }
    @Transactional
    public Asn create(UUID tenant,UUID actor,UUID order,Create value) {
        if(value==null || value.requestKey()==null || value.lineId()==null ||
           value.warehouseId()==null || value.quantity()<1 || value.quantity()>1_000_000)
            throw bad("Invalid supplier ASN payload");
        validateEta(value.expectedArrivalDate());
        String tracking=reference(value.trackingReference());
        activeOrder(tenant,order); // same PO lock as actual receiving and transitions
        List<UUID> existing=jdbc.queryForList("""
            SELECT id FROM erp_purchase_asn WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,value.requestKey());
        if(!existing.isEmpty()) {
            Asn old=get(tenant,existing.getFirst());
            if(!old.orderId().equals(order.toString())
                || !old.lineId().equals(value.lineId().toString())
                || !old.warehouseId().equals(value.warehouseId().toString())
                || old.quantity()!=value.quantity()
                || !old.expectedArrivalDate().equals(value.expectedArrivalDate().toString())
                || !java.util.Objects.equals(old.trackingReference(),tracking))
                throw conflict("ASN request key has different contents");
            return old;
        }
        List<Integer> ordered=jdbc.queryForList("""
            SELECT ordered_quantity FROM erp_purchase_order_line
            WHERE tenant_id=? AND purchase_order_id=? AND id=?
            """,Integer.class,tenant,order,value.lineId());
        if(ordered.size()!=1)throw missing();
        List<UUID> warehouses=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR SHARE
            """,UUID.class,tenant,value.warehouseId());
        if(warehouses.size()!=1)throw missing();
        Long committed=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_purchase_asn
            WHERE tenant_id=? AND purchase_order_id=? AND purchase_order_line_id=?
              AND status<>'CANCELLED'
            """,Long.class,tenant,order,value.lineId());
        // Legacy/manual receipts without ASN linkage are already arrived units,
        // not unallocated shipment capacity. Do not promise those units again.
        Long unlinked=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_purchase_receipt
            WHERE tenant_id=? AND purchase_order_id=? AND purchase_order_line_id=?
              AND asn_id IS NULL
            """,Long.class,tenant,order,value.lineId());
        if((committed==null?0:committed)+(unlinked==null?0:unlinked)+value.quantity()
            >ordered.getFirst())
            throw conflict("Supplier ASN exceeds ordered units after existing receipts and notices");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_purchase_asn
              (id,tenant_id,request_key,purchase_order_id,purchase_order_line_id,
               warehouse_id,quantity,expected_arrival_date,tracking_reference,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,value.requestKey(),order,value.lineId(),value.warehouseId(),
            value.quantity(),value.expectedArrivalDate(),tracking,actor);
        event(tenant,actor,id,"CREATED",value.quantity(),null,
            value.expectedArrivalDate(),tracking);
        return get(tenant,id);
    }
    @Transactional
    public Asn transition(UUID tenant,UUID actor,UUID order,UUID asnId,String action) {
        if(!"ship".equals(action) && !"cancel".equals(action))
            throw bad("Unsupported supplier ASN action");
        activeOrder(tenant,order);
        List<String> statuses=jdbc.query("""
            SELECT status FROM erp_purchase_asn
            WHERE tenant_id=? AND purchase_order_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getString(1),tenant,order,asnId);
        if(statuses.size()!=1)throw missing();
        String status=statuses.getFirst();
        if("ship".equals(action) && "SHIPPED".equals(status))return get(tenant,asnId);
        if("cancel".equals(action) && "CANCELLED".equals(status))return get(tenant,asnId);
        if("CANCELLED".equals(status) || ("ship".equals(action) && !"DRAFT".equals(status)))
            throw conflict("Supplier ASN status changed");
        if("cancel".equals(action)) {
            Long receiptCount=jdbc.queryForObject("""
                SELECT COUNT(*) FROM erp_purchase_receipt
                WHERE tenant_id=? AND asn_id=?
                """,Long.class,tenant,asnId);
            if(receiptCount!=null && receiptCount>0)
                throw conflict("Cannot cancel an ASN with linked physical receipts");
            jdbc.update("""
                UPDATE erp_purchase_asn SET status='CANCELLED',updated_at=now()
                WHERE tenant_id=? AND id=?
                """,tenant,asnId);
            event(tenant,actor,asnId,"CANCELLED",null,null,null,null);
        } else {
            jdbc.update("""
                UPDATE erp_purchase_asn
                SET status='SHIPPED',dispatch_reported_at=now(),updated_at=now()
                WHERE tenant_id=? AND id=?
                """,tenant,asnId);
            event(tenant,actor,asnId,"SHIPMENT_REPORTED",null,null,null,
                "Supplier-reported dispatch; not carrier verified");
        }
        return get(tenant,asnId);
    }
    @Transactional
    public Asn updateEta(UUID tenant,UUID actor,UUID order,UUID asnId,ChangeEta value) {
        if(value==null || value.expectedPreviousEta()==null)throw bad("Expected previous ETA required");
        validateEta(value.expectedArrivalDate());
        String explanation=reason(value.reason());
        activeOrder(tenant,order);
        Asn original=get(tenant,asnId);
        if(!original.orderId().equals(order.toString()) || "CANCELLED".equals(original.status()))
            throw missing();
        if(!original.expectedArrivalDate().equals(value.expectedPreviousEta().toString()))
            throw conflict("Supplier ASN ETA changed, refresh before updating");
        if(value.expectedArrivalDate().equals(value.expectedPreviousEta()))return original;
        jdbc.update("""
            UPDATE erp_purchase_asn
            SET expected_arrival_date=?,updated_at=now()
            WHERE tenant_id=? AND id=?
            """,value.expectedArrivalDate(),tenant,asnId);
        event(tenant,actor,asnId,"ETA_UPDATED",null,value.expectedPreviousEta(),
            value.expectedArrivalDate(),explanation);
        return get(tenant,asnId);
    }

    private static Finding finding(ResultSet rs,int i) throws SQLException {
        return new Finding(rs.getObject("id",UUID.class).toString(),
            rs.getObject("asn_id",UUID.class).toString(),
            rs.getObject("purchase_order_id",UUID.class).toString(),
            rs.getString("tracking_reference"),rs.getString("rule_code"),
            rs.getString("state"),rs.getObject("expected_arrival_date",LocalDate.class).toString(),
            instant(rs,"last_seen_at"));
    }
    @Transactional(readOnly=true)
    public List<Finding> findings(UUID tenant,String filter) {
        if(!"ACTIVE".equals(filter) && !"ALL".equals(filter))
            throw bad("filter must be ACTIVE or ALL");
        return jdbc.query("""
            SELECT f.id,f.asn_id,a.purchase_order_id,a.tracking_reference,
              f.rule_code,f.state,a.expected_arrival_date,f.last_seen_at
            FROM erp_purchase_asn_finding f
            JOIN erp_purchase_asn a ON a.tenant_id=f.tenant_id AND a.id=f.asn_id
            WHERE f.tenant_id=? AND (?='ALL' OR f.state IN ('OPEN','ACKNOWLEDGED'))
            ORDER BY CASE f.state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                          WHEN 'DONE' THEN 2 ELSE 3 END,
                f.changed_at DESC,f.id DESC LIMIT 100
            """,SupplierAsnService::finding,tenant,filter);
    }
    private static final String UPSERT = """
        ON CONFLICT (tenant_id,asn_id,rule_code) DO UPDATE
        SET last_scan_id=EXCLUDED.last_scan_id,last_seen_at=now(),
          state=CASE WHEN erp_purchase_asn_finding.state='CLEARED' THEN 'OPEN'
                     ELSE erp_purchase_asn_finding.state END,
          changed_at=CASE WHEN erp_purchase_asn_finding.state='CLEARED' THEN now()
                         ELSE erp_purchase_asn_finding.changed_at END
        """;
    private static final String FINDING_SOURCE = """
        FROM erp_purchase_asn a
        JOIN erp_purchase_order o ON o.tenant_id=a.tenant_id AND o.id=a.purchase_order_id
        WHERE a.tenant_id=? AND a.status='SHIPPED'
          AND o.status IN ('APPROVED','RECEIVING')
          AND a.quantity>(SELECT COALESCE(SUM(r.quantity),0)
            FROM erp_purchase_receipt r
            WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id)
        """;
    private static final String OVERDUE_SQL = """
        INSERT INTO erp_purchase_asn_finding
          (id,tenant_id,asn_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),a.tenant_id,a.id,'SUPPLIER_ASN_OVERDUE',?
        """+FINDING_SOURCE+"""
          AND a.expected_arrival_date<CURRENT_DATE
        """+UPSERT;
    private static final String DUE_SOON_SQL = """
        INSERT INTO erp_purchase_asn_finding
          (id,tenant_id,asn_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),a.tenant_id,a.id,'SUPPLIER_ASN_DUE_SOON',?
        """+FINDING_SOURCE+"""
          AND a.expected_arrival_date BETWEEN CURRENT_DATE
            AND CURRENT_DATE+3
        """+UPSERT;
    @Transactional
    public ScanResult scan(UUID tenant,UUID actor,String trigger) {
        if(!"MANUAL".equals(trigger) && !"SCHEDULED".equals(trigger))
            throw bad("Invalid supplier ASN scan trigger");
        List<UUID> locked=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(locked.size()!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        UUID id=UUID.randomUUID();
        int matched=jdbc.update(OVERDUE_SQL,id,tenant);
        matched+=jdbc.update(DUE_SOON_SQL,id,tenant);
        int cleared=jdbc.update("""
            UPDATE erp_purchase_asn_finding SET state='CLEARED',changed_at=now()
            WHERE tenant_id=? AND last_scan_id<>? AND state<>'CLEARED'
            """,tenant,id);
        jdbc.update("""
            INSERT INTO erp_purchase_asn_scan_run
              (id,tenant_id,actor_user_id,trigger_type,matched_count,cleared_count)
            VALUES (?,?,?,?,?,?)
            """,id,tenant,actor,trigger,matched,cleared);
        return new ScanResult(id.toString(),trigger,matched,cleared);
    }
    @Transactional
    public FindingChange changeState(UUID tenant,UUID actor,UUID findingId,String next) {
        if(next==null || !List.of("OPEN","ACKNOWLEDGED","DONE").contains(next))
            throw bad("Invalid supplier ASN finding state");
        List<String> rows=jdbc.query("""
            SELECT state FROM erp_purchase_asn_finding
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getString(1),tenant,findingId);
        if(rows.size()!=1)throw missing();
        String previous=rows.getFirst();
        if("CLEARED".equals(previous))throw conflict("Supplier ASN finding has cleared");
        if(!previous.equals(next)) {
            jdbc.update("""
                UPDATE erp_purchase_asn_finding
                SET state=?,changed_at=now() WHERE tenant_id=? AND id=?
                """,next,tenant,findingId);
            jdbc.update("""
                INSERT INTO erp_purchase_asn_finding_audit
                  (id,tenant_id,finding_id,actor_user_id,previous_state,new_state)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,findingId,actor,previous,next);
        }
        return new FindingChange(findingId.toString(),next);
    }
}
