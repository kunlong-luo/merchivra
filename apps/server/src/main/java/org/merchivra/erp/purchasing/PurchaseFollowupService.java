package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    private record RankedFinding(Finding finding,int priority,String changedAt) {}
    private static int priority(String state) {
        return switch(state) {
            case "OPEN" -> 0;
            case "ACKNOWLEDGED" -> 1;
            case "DONE" -> 2;
            default -> 3;
        };
    }
    private static OffsetDateTime cursorTime(String value) {
        try {return Instant.parse(value).atOffset(ZoneOffset.UTC);}
        catch(Exception e) {throw LocalKeysetPage.bad("Invalid follow-up cursor timestamp");}
    }
    private static UUID cursorId(String value) {
        try {return UUID.fromString(value);}
        catch(IllegalArgumentException e) {throw LocalKeysetPage.bad("Invalid follow-up cursor ID");}
    }
    private static final String FINDINGS_RANK = """
        CASE f.state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
          WHEN 'DONE' THEN 2 ELSE 3 END
        """;
    private static final String FINDINGS_SORT = " ORDER BY "+FINDINGS_RANK+
        ",f.changed_at DESC,f.id DESC LIMIT ?";
    private static final String FINDINGS_SELECT = """
        SELECT f.id,o.id AS order_id,o.order_number,l.id AS line_id,
          sku.internal_sku,sup.display_name AS supplier_name,
          f.rule_code,f.state,o.approved_at,l.ordered_quantity,
          a.active_units,r.received_units,r.unlinked_units,f.last_seen_at,
          f.changed_at
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
        """;
    /**
     * V125 PG18 release-gate: find at most (limit + 1) immutable finding
     * identifiers BEFORE joining ASN and receipt aggregate LATERAL sources.
     * Those source sums must never be executed across all historical rows
     * merely to display the next 50. Materialized CTE preserves the exact
     * state-rank/changed_at/id keyset and snapshot inside this one SQL statement.
     */
    private static final String PAGED_FINDING_IDS = """
        WITH page_ids AS MATERIALIZED (
          SELECT f.id FROM erp_purchase_followup_finding f
          WHERE f.tenant_id=? AND (?='ALL' OR f.state IN ('OPEN','ACKNOWLEDGED'))
        """;
    private static final String PAGED_FINDINGS_SELECT=FINDINGS_SELECT.replace(
        "FROM erp_purchase_followup_finding f",
        "FROM page_ids page JOIN erp_purchase_followup_finding f ON f.id=page.id");

    private static Finding mapFinding(ResultSet rs,int i) throws SQLException {
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
    }
    private static void validateFilter(String filter) {
        if(!List.of("ACTIVE","ALL").contains(filter))throw LocalKeysetPage.bad(
            "Invalid follow-up filter");
    }
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
        validateFilter(filter);
        return jdbc.query(FINDINGS_SELECT+" ORDER BY "+FINDINGS_RANK+
            ",f.changed_at DESC,f.id DESC LIMIT 100",
            PurchaseFollowupService::mapFinding,tenant,filter);
    }

    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Finding> findingsPage(UUID tenant,String filter,
        int requested,String rawCursor) {
        validateFilter(filter);
        int limit=LocalKeysetPage.limit(requested);
        String[] cursor=LocalKeysetPage.decode(rawCursor,5);
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(filter);
        String where="";
        if(cursor!=null) {
            if(!tenant.toString().equals(cursor[0])||!filter.equals(cursor[1]))
                throw LocalKeysetPage.bad("Follow-up cursor scope mismatch");
            int rank;
            try {rank=Integer.parseInt(cursor[2]);}
            catch(NumberFormatException e) {throw LocalKeysetPage.bad("Invalid follow-up state rank");}
            if(rank<0||rank>3)throw LocalKeysetPage.bad("Invalid follow-up state rank");
            where=" AND ("+FINDINGS_RANK+" > ? OR ("+FINDINGS_RANK+
                " = ? AND (f.changed_at,f.id)<(?,?)))";
            args.add(rank);args.add(rank);
            args.add(cursorTime(cursor[3]));args.add(cursorId(cursor[4]));
        }
        // The first two arguments and optional keyset refer to page_ids.
        args.add(limit+1);
        // The detailed select is Session-tenant-scoped again after page_ids;
        // this avoids a cross-tenant join even if a future FK loses its scope.
        args.add(tenant);
        args.add(filter);
        List<RankedFinding> rows=jdbc.query(
            PAGED_FINDING_IDS+where+FINDINGS_SORT+"""
            )
            """+PAGED_FINDINGS_SELECT+" ORDER BY "+FINDINGS_RANK+
                ",f.changed_at DESC,f.id DESC",
            (rs,i)->new RankedFinding(mapFinding(rs,i),
                priority(rs.getString("state")),
                rs.getObject("changed_at",OffsetDateTime.class).toInstant().toString()),
            args.toArray());
        var page=LocalKeysetPage.pack(rows,limit,row->LocalKeysetPage.encode(
            tenant.toString(),filter,Integer.toString(row.priority()),
            row.changedAt(),row.finding().id()));
        return new LocalKeysetPage.Page<>(
            page.items().stream().map(RankedFinding::finding).toList(),
            page.nextCursor(),page.hasMore(),page.pageSize());
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
