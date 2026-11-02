package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Pre-dispatch follow-ups only: V43 is responsible for SHIPPED ASN ETA alerts. */
@Service
public class PurchaseFollowupService {
    private final JdbcTemplate jdbc;
    private final int afterDays;
    public PurchaseFollowupService(JdbcTemplate jdbc,
        @Value("$"+"{merchivra.purchase-followups.after-days:7}") int afterDays) {
        if(afterDays<1 || afterDays>60)throw new IllegalArgumentException("Invalid follow-up days");
        this.jdbc=jdbc;this.afterDays=afterDays;
    }
    public record Finding(String id,String orderId,String orderNumber,String lineId,
        String internalSku,String supplierName,String ruleCode,String state,
        String approvedAt,long orderedQuantity,long receivedQuantity,
        long activeAsnQuantity,long uncoveredQuantity,String lastSeenAt) {}
    public record ScanResult(String id,String triggerType,int matchedCount,int clearedCount) {}
    public record FindingChange(String id,String state) {}
    private static final String SOURCES = """
        FROM erp_purchase_order_line l
        JOIN erp_purchase_order o ON o.tenant_id=l.tenant_id
          AND o.id=l.purchase_order_id
        LEFT JOIN LATERAL (
          SELECT COALESCE(SUM(a.quantity),0)::bigint AS active_units,
            COALESCE(SUM(a.quantity) FILTER (WHERE a.status='DRAFT'),0)::bigint AS draft_units,
            COALESCE(SUM(a.quantity) FILTER (WHERE a.status='SHIPPED'),0)::bigint AS shipped_units
          FROM erp_purchase_asn a
          WHERE a.tenant_id=l.tenant_id AND a.purchase_order_line_id=l.id
            AND a.status<>'CANCELLED'
        ) a ON TRUE
        LEFT JOIN LATERAL (
          SELECT COALESCE(SUM(r.quantity),0)::bigint AS received_units,
            COALESCE(SUM(r.quantity) FILTER (WHERE r.asn_id IS NULL),0)::bigint AS unlinked_units
          FROM erp_purchase_receipt r WHERE r.tenant_id=l.tenant_id
            AND r.purchase_order_line_id=l.id
        ) r ON TRUE
        """;
    private static final String GATE = """
        WHERE l.tenant_id=? AND o.status IN ('APPROVED','RECEIVING')
          AND o.approved_at IS NOT NULL
          AND (o.approved_at AT TIME ZONE 'UTC')::date <=
            (now() AT TIME ZONE 'UTC')::date - ?
          AND l.ordered_quantity>r.received_units
        """;
    private static final String UPSERT = """
        ON CONFLICT(tenant_id,purchase_order_line_id,rule_code) DO UPDATE
        SET last_scan_id=EXCLUDED.last_scan_id,last_seen_at=now(),
          state=CASE WHEN erp_purchase_followup_finding.state='CLEARED' THEN 'OPEN'
                     ELSE erp_purchase_followup_finding.state END,
          changed_at=CASE WHEN erp_purchase_followup_finding.state='CLEARED' THEN now()
                          ELSE erp_purchase_followup_finding.changed_at END
        """;
    private static final String COVERAGE = """
        INSERT INTO erp_purchase_followup_finding
          (id,tenant_id,purchase_order_line_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),l.tenant_id,l.id,'ASN_COVERAGE_GAP',?
        """+SOURCES+GATE+"""
          AND l.ordered_quantity>a.active_units+r.unlinked_units
        """+UPSERT;
    private static final String UNDISPATCHED = """
        INSERT INTO erp_purchase_followup_finding
          (id,tenant_id,purchase_order_line_id,rule_code,last_scan_id)
        SELECT gen_random_uuid(),l.tenant_id,l.id,'ASN_NOT_DISPATCHED',?
        """+SOURCES+GATE+"""
          AND l.ordered_quantity<=a.active_units+r.unlinked_units
          AND a.draft_units>0 AND a.shipped_units=0
        """+UPSERT;
    @Transactional
    public ScanResult scan(UUID tenant,UUID actor,String trigger) {
        if(!List.of("MANUAL","SCHEDULED").contains(trigger))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var locked=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(locked.size()!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        UUID id=UUID.randomUUID();
        int matched=jdbc.update(COVERAGE,id,tenant,afterDays);
        matched+=jdbc.update(UNDISPATCHED,id,tenant,afterDays);
        int cleared=jdbc.update("""
            UPDATE erp_purchase_followup_finding SET state='CLEARED',changed_at=now()
            WHERE tenant_id=? AND last_scan_id<>? AND state<>'CLEARED'
            """,tenant,id);
        jdbc.update("""
            INSERT INTO erp_purchase_followup_scan
              (id,tenant_id,actor_user_id,trigger_type,matched_count,cleared_count)
            VALUES (?,?,?,?,?,?)
            """,id,tenant,actor,trigger,matched,cleared);
        return new ScanResult(id.toString(),trigger,matched,cleared);
    }
    @Transactional(readOnly=true)
    public List<Finding> findings(UUID tenant,String filter) {
        if(!List.of("ACTIVE","ALL").contains(filter))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return jdbc.query("""
            SELECT f.id,o.id AS order_id,o.order_number,l.id AS line_id,
              sku.internal_sku,sup.display_name AS supplier_name,
              f.rule_code,f.state,o.approved_at,l.ordered_quantity,
              a.active_units,r.received_units,r.unlinked_units,f.last_seen_at
            FROM erp_purchase_followup_finding f
            JOIN erp_purchase_order_line l ON l.tenant_id=f.tenant_id
              AND l.id=f.purchase_order_line_id
            JOIN erp_purchase_order o ON o.tenant_id=l.tenant_id AND o.id=l.purchase_order_id
            JOIN erp_supplier sup ON sup.tenant_id=o.tenant_id AND sup.id=o.supplier_id
            JOIN product_sku sku ON sku.tenant_id=l.tenant_id AND sku.id=l.product_sku_id
            LEFT JOIN LATERAL (
              SELECT COALESCE(SUM(a.quantity),0)::bigint AS active_units
              FROM erp_purchase_asn a WHERE a.tenant_id=l.tenant_id
                AND a.purchase_order_line_id=l.id AND a.status<>'CANCELLED'
            ) a ON TRUE
            LEFT JOIN LATERAL (
              SELECT COALESCE(SUM(r.quantity),0)::bigint AS received_units,
                COALESCE(SUM(r.quantity) FILTER (WHERE r.asn_id IS NULL),0)::bigint AS unlinked_units
              FROM erp_purchase_receipt r WHERE r.tenant_id=l.tenant_id
                AND r.purchase_order_line_id=l.id
            ) r ON TRUE
            WHERE f.tenant_id=? AND (?='ALL' OR f.state IN ('OPEN','ACKNOWLEDGED'))
            ORDER BY CASE f.state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                 WHEN 'DONE' THEN 2 ELSE 3 END,f.changed_at DESC,f.id DESC LIMIT 100
            """,(rs,i)->{
                long ordered=rs.getLong("ordered_quantity");
                long asn=rs.getLong("active_units"),unlinked=rs.getLong("unlinked_units");
                OffsetDateTime approved=rs.getObject("approved_at",OffsetDateTime.class);
                OffsetDateTime seen=rs.getObject("last_seen_at",OffsetDateTime.class);
                return new Finding(rs.getObject("id",UUID.class).toString(),
                    rs.getObject("order_id",UUID.class).toString(),
                    rs.getString("order_number"),rs.getObject("line_id",UUID.class).toString(),
                    rs.getString("internal_sku"),rs.getString("supplier_name"),
                    rs.getString("rule_code"),rs.getString("state"),
                    approved==null?null:approved.toInstant().toString(),
                    ordered,rs.getLong("received_units"),asn,
                    Math.max(0,ordered-asn-unlinked),seen.toInstant().toString());
            },tenant,filter);
    }
    @Transactional
    public FindingChange changeState(UUID tenant,UUID actor,UUID id,String target) {
        if(!List.of("OPEN","ACKNOWLEDGED","DONE").contains(target))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=jdbc.query("""
            SELECT state FROM erp_purchase_followup_finding
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getString(1),tenant,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String old=rows.getFirst();
        if("CLEARED".equals(old))throw new ResponseStatusException(HttpStatus.CONFLICT);
        if(!old.equals(target)) {
            jdbc.update("""
                UPDATE erp_purchase_followup_finding SET state=?,changed_at=now()
                WHERE tenant_id=? AND id=?
                """,target,tenant,id);
            jdbc.update("""
                INSERT INTO erp_purchase_followup_audit
                  (id,tenant_id,finding_id,actor_user_id,old_state,new_state)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,id,actor,old,target);
        }
        return new FindingChange(id.toString(),target);
    }
}
