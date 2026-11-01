package org.merchivra.erp.operations;

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

/**
 * V10 advisory-only operational automation. Reads existing tenant-scoped facts;
 * NEVER calls Amazon, sends customer messages, or mutates source orders/inventory.
 * Manual scans and optional scheduler use the same serialized tenant transaction.
 */
@Service
public class OperationsAutomationService {
    private final JdbcTemplate jdbc;
    private final int lowStockThreshold;

    public OperationsAutomationService(JdbcTemplate jdbc,
        @Value("${merchivra.operations.low-stock-threshold:3}") int lowStockThreshold) {
        if (lowStockThreshold < 0 || lowStockThreshold > 10000) {
            throw new IllegalArgumentException("Low stock threshold must be 0..10000");
        }
        this.jdbc = jdbc;
        this.lowStockThreshold = lowStockThreshold;
    }

    public record Finding(String id, String marketplaceId, String ruleCode, String sourceKey,
                          String title, String severity, String state, String dataOrigin,
                          String lastSeenAt,String assignedTo,String snoozedUntil) {}
    public record ScanResult(String id, String triggerType, int matchedCount,
                             int clearedCount) {}
    public record Summary(long open, long acknowledged, long done, long cleared,
                          long snoozed) {}
    public record FindingChange(String id, String state) {}
    public record Attention(String id,String state,String assignedTo,String snoozedUntil) {}
    public record AttentionAudit(String action,String actorId,String oldAssignee,
        String newAssignee,String oldSnoozedUntil,String newSnoozedUntil,
        String note,String createdAt) {}
    private record LockRow(String state,UUID assignedTo,OffsetDateTime snoozedUntil) {}


    @Transactional(readOnly = true)
    public List<Finding> findings(UUID tenant, String filter) {
        if (!"ACTIVE".equals(filter) && !"ALL".equals(filter) && !"ACTIONABLE".equals(filter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filter must be ACTIVE, ACTIONABLE or ALL");
        }
        return jdbc.query("""
            SELECT id, seller_marketplace_id, rule_code, source_key, title,
                   severity, state, data_origin, last_seen_at, assigned_to, snoozed_until
            FROM ops_finding
            WHERE tenant_id = ?
              AND (? = 'ALL' OR state IN ('OPEN','ACKNOWLEDGED'))
              AND (? <> 'ACTIONABLE' OR snoozed_until IS NULL OR snoozed_until <= now())
            ORDER BY CASE state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                                WHEN 'DONE' THEN 2 ELSE 3 END,
                     changed_at DESC, id DESC
            LIMIT 100
            """, OperationsAutomationService::mapFinding, tenant, filter, filter);
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID tenant) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE state = 'OPEN') AS open_count,
                   COUNT(*) FILTER (WHERE state = 'ACKNOWLEDGED') AS acknowledged_count,
                   COUNT(*) FILTER (WHERE state = 'DONE') AS done_count,
                   COUNT(*) FILTER (WHERE state = 'CLEARED') AS cleared_count,
                   COUNT(*) FILTER (WHERE state IN ('OPEN','ACKNOWLEDGED')
                                     AND snoozed_until > now()) AS snoozed_count
            FROM ops_finding WHERE tenant_id = ?
            """, (rs, row) -> new Summary(rs.getLong("open_count"),
                rs.getLong("acknowledged_count"), rs.getLong("done_count"),
                rs.getLong("cleared_count"), rs.getLong("snoozed_count")), tenant);
    }

    @Transactional
    public ScanResult scan(UUID tenant, UUID actor, String trigger) {
        if (!"MANUAL".equals(trigger) && !"SCHEDULED".equals(trigger)) {
            throw new IllegalArgumentException("Invalid scan trigger");
        }
        // Lock tenant row to serialize scans across replicas and user/scheduler.
        // Revoked/inactive tenants fail closed and never create findings.
        List<UUID> locked = jdbc.queryForList("""
            SELECT id FROM tenant WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
            """, UUID.class, tenant);
        if (locked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant inactive");
        }
        UUID scanId = UUID.randomUUID();
        int matched = 0;
        matched += jdbc.update(LISTINGS_SQL, scanId, tenant);
        matched += jdbc.update(INVENTORY_UNKNOWN_SQL, scanId, tenant);
        matched += jdbc.update(LOW_STOCK_SQL, scanId, tenant, lowStockThreshold);
        matched += jdbc.update(REFUNDS_SQL, scanId, tenant);
        matched += jdbc.update(FBA_REVIEW_SQL, scanId, tenant);
        matched += jdbc.update(ADS_REVIEW_SQL, scanId, tenant);

        // Only rows absent from a COMPLETE scan are cleared. DONE stays
        // suppressed until condition disappears, at which point CLEARED.
        int cleared = jdbc.update("""
            UPDATE ops_finding SET state = 'CLEARED', changed_at = now()
            WHERE tenant_id = ? AND last_scan_id <> ? AND state <> 'CLEARED'
            """, tenant, scanId);
        jdbc.update("""
            INSERT INTO ops_automation_run
              (id, tenant_id, actor_user_id, trigger_type, matched_count, cleared_count)
            VALUES (?, ?, ?, ?, ?, ?)
            """, scanId, tenant, actor, trigger, matched, cleared);
        return new ScanResult(scanId.toString(), trigger, matched, cleared);
    }

    @Transactional
    public FindingChange changeState(UUID tenant, UUID actor, UUID findingId, String requested) {
        if (!List.of("OPEN", "ACKNOWLEDGED", "DONE").contains(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid state");
        }
        List<String> states = jdbc.query("""
            SELECT state FROM ops_finding WHERE tenant_id = ? AND id = ? FOR UPDATE
            """, (rs, row) -> rs.getString("state"), tenant, findingId);
        if (states.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found");
        }
        String previous = states.getFirst();
        if ("CLEARED".equals(previous)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Finding already cleared");
        }
        if (!previous.equals(requested)) {
            jdbc.update("""
                UPDATE ops_finding SET state = ?, changed_at = now()
                WHERE tenant_id = ? AND id = ?
                """, requested, tenant, findingId);
            jdbc.update("""
                INSERT INTO ops_finding_audit
                  (id, tenant_id, finding_id, actor_user_id, old_state, new_state)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenant, findingId, actor, previous, requested);
        }
        return new FindingChange(findingId.toString(), requested);
    }

    private static Finding mapFinding(ResultSet rs, int row) throws SQLException {
        OffsetDateTime seen = rs.getObject("last_seen_at", OffsetDateTime.class);
        return new Finding(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("seller_marketplace_id", UUID.class).toString(),
            rs.getString("rule_code"), rs.getString("source_key"), rs.getString("title"),
            rs.getString("severity"), rs.getString("state"), rs.getString("data_origin"),
            seen.toInstant().toString(),
            rs.getObject("assigned_to",UUID.class)==null?null:
                rs.getObject("assigned_to",UUID.class).toString(),
            rs.getObject("snoozed_until",OffsetDateTime.class)==null?null:
                rs.getObject("snoozed_until",OffsetDateTime.class).toInstant().toString());
    }

    private static final String UPSERT = """
        ON CONFLICT (tenant_id, seller_marketplace_id, rule_code, source_key)
        DO UPDATE SET last_scan_id = EXCLUDED.last_scan_id,
                      last_seen_at = now(),
                      severity = EXCLUDED.severity,
                      data_origin = EXCLUDED.data_origin,
                      state = CASE WHEN ops_finding.state = 'CLEARED' THEN 'OPEN'
                                   ELSE ops_finding.state END,
                      assigned_to = CASE WHEN ops_finding.state='CLEARED' THEN NULL
                                         ELSE ops_finding.assigned_to END,
                      claimed_at = CASE WHEN ops_finding.state='CLEARED' THEN NULL
                                        ELSE ops_finding.claimed_at END,
                      snoozed_until = CASE WHEN ops_finding.state='CLEARED' THEN NULL
                                           ELSE ops_finding.snoozed_until END,
                      changed_at = CASE WHEN ops_finding.state = 'CLEARED' THEN now()
                                        ELSE ops_finding.changed_at END
        """;

    private static final String LISTINGS_SQL = """
        INSERT INTO ops_finding (id, tenant_id, seller_marketplace_id, rule_code,
                                 source_key, title, severity, data_origin, last_scan_id)
        SELECT gen_random_uuid(), l.tenant_id, l.seller_marketplace_id,
               'UNMAPPED_LISTING', l.id::text, 'Listing 映射待确认',
               'WARNING', l.data_origin, ?
        FROM marketplace_listing l
        WHERE l.tenant_id = ? AND l.mapping_status IN ('UNMAPPED','CONFLICT')
        """ + UPSERT;

    private static final String INVENTORY_SOURCE = """
        FROM (
          SELECT DISTINCT ON (i.seller_marketplace_id, i.seller_sku_raw)
                 i.tenant_id, i.seller_marketplace_id, i.seller_sku_raw,
                 i.quality, i.fulfillable, i.data_origin
          FROM fba_inventory_observation i
          WHERE i.tenant_id = ?
          ORDER BY i.seller_marketplace_id, i.seller_sku_raw,
                   i.observed_at DESC NULLS LAST, i.ingested_at DESC, i.id DESC
        ) latest
        """;

    private static final String INVENTORY_UNKNOWN_SQL = """
        INSERT INTO ops_finding (id, tenant_id, seller_marketplace_id, rule_code,
                                 source_key, title, severity, data_origin, last_scan_id)
        SELECT gen_random_uuid(), latest.tenant_id, latest.seller_marketplace_id,
               'INVENTORY_UNKNOWN', latest.seller_sku_raw, 'FBA 库存数据不完整',
               'WARNING', latest.data_origin, ?
        """ + INVENTORY_SOURCE + """
        WHERE latest.quality IN ('UNKNOWN','STALE','PARTIAL')
              OR latest.fulfillable IS NULL
        """ + UPSERT;

    private static final String LOW_STOCK_SQL = """
        INSERT INTO ops_finding (id, tenant_id, seller_marketplace_id, rule_code,
                                 source_key, title, severity, data_origin, last_scan_id)
        SELECT gen_random_uuid(), latest.tenant_id, latest.seller_marketplace_id,
               'LOW_STOCK', latest.seller_sku_raw, 'FBA 可售库存偏低',
               'CRITICAL', latest.data_origin, ?
        """ + INVENTORY_SOURCE + """
        WHERE latest.quality = 'OBSERVED' AND latest.fulfillable IS NOT NULL
          AND latest.fulfillable <= ?
        """ + UPSERT;

    private static final String REFUNDS_SQL = """
        INSERT INTO ops_finding (id, tenant_id, seller_marketplace_id, rule_code,
                                 source_key, title, severity, data_origin, last_scan_id)
        SELECT gen_random_uuid(), o.tenant_id, o.seller_marketplace_id,
               'REFUND_REVIEW', o.id::text, '退款订单待核对',
               'INFO', o.data_origin, ?
        FROM sales_order o
        WHERE o.tenant_id = ? AND o.refund_status IN ('PARTIAL','FULL')
        """ + UPSERT;
    private static final String FBA_REVIEW_SQL = """
        INSERT INTO ops_finding (id,tenant_id,seller_marketplace_id,rule_code,
                                 source_key,title,severity,data_origin,last_scan_id)
        SELECT gen_random_uuid(),s.tenant_id,s.seller_marketplace_id,
               'FBA_INBOUND_REVIEW',s.id::text,'本地 FBA 货件待入仓复核',
               'WARNING',CASE WHEN s.evidence_origin='MOCK' THEN 'MOCK'
                              ELSE 'UNVERIFIED' END,?
        FROM fba_inbound_shipment s
        WHERE s.tenant_id=? AND s.state='REVIEWING'
        """ + UPSERT;

    private static final String ADS_REVIEW_SQL = """
        INSERT INTO ops_finding (id,tenant_id,seller_marketplace_id,rule_code,
                                 source_key,title,severity,data_origin,last_scan_id)
        SELECT gen_random_uuid(),r.tenant_id,r.seller_marketplace_id,
               'ADS_REPORT_REVIEW',r.id::text,'离线 Ads 日报待独立审核',
               'WARNING',CASE WHEN r.evidence_origin='MOCK' THEN 'MOCK'
                              ELSE 'UNVERIFIED' END,?
        FROM ads_report_import r
        WHERE r.tenant_id=? AND r.state='STAGED'
        """ + UPSERT;

    private LockRow lockFinding(UUID tenant,UUID id) {
        var values=jdbc.query("""
            SELECT state,assigned_to,snoozed_until FROM ops_finding
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->new LockRow(rs.getString(1),rs.getObject(2,UUID.class),
                rs.getObject(3,OffsetDateTime.class)),tenant,id);
        if(values.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Finding absent in current tenant");
        if("CLEARED".equals(values.getFirst().state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Finding cleared");
        return values.getFirst();
    }

    private static String at(OffsetDateTime when) {
        return when==null?null:when.toInstant().toString();
    }

    private void auditAttention(UUID tenant,UUID id,UUID actor,String action,
        LockRow before,UUID next,OffsetDateTime nextUntil,String note) {
        jdbc.update("""
            INSERT INTO ops_finding_attention_audit
             (id,tenant_id,finding_id,actor_user_id,action,
              old_assignee,new_assignee,old_snoozed_until,new_snoozed_until,note)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,action,
            before.assignedTo(),next,before.snoozedUntil(),nextUntil,note);
    }

    /** Assign only to the logged-in Owner; never trust a browser-supplied user ID. */
    @Transactional
    public Attention claim(UUID tenant,UUID actor,UUID id) {
        LockRow before=lockFinding(tenant,id);
        if(!"OPEN".equals(before.state())&&!"ACKNOWLEDGED".equals(before.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only unresolved findings can be claimed");
        if(before.assignedTo()!=null&&!before.assignedTo().equals(actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Claimed by another Owner");
        if(before.assignedTo()==null) {
            // Owner/active membership is checked by Session access; FK is defense-in-depth.
            jdbc.update("""
                UPDATE ops_finding SET assigned_to=?,claimed_at=now(),
                 state=CASE WHEN state='OPEN' THEN 'ACKNOWLEDGED' ELSE state END,
                 changed_at=now() WHERE tenant_id=? AND id=?
                """,actor,tenant,id);
            auditAttention(tenant,id,actor,"CLAIMED",before,actor,before.snoozedUntil(),null);
        }
        return attention(tenant,id);
    }

    @Transactional
    public Attention release(UUID tenant,UUID actor,UUID id) {
        LockRow before=lockFinding(tenant,id);
        if(before.assignedTo()==null)return attention(tenant,id);
        if(!before.assignedTo().equals(actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only assigned Owner may release");
        jdbc.update("""
            UPDATE ops_finding SET assigned_to=NULL,claimed_at=NULL,changed_at=now()
            WHERE tenant_id=? AND id=?
            """,tenant,id);
        auditAttention(tenant,id,actor,"RELEASED",before,null,before.snoozedUntil(),null);
        return attention(tenant,id);
    }

    @Transactional
    public Attention snooze(UUID tenant,UUID actor,UUID id,int minutes,String note) {
        if(minutes!=15&&minutes!=60&&minutes!=240&&minutes!=1440)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supported snooze 15,60,240,1440 min");
        if(note==null||note.strip().length()<10||note.length()>300||
          note.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review note length 10..300");
        LockRow before=lockFinding(tenant,id);
        if(!"OPEN".equals(before.state())&&!"ACKNOWLEDGED".equals(before.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only unresolved findings can be snoozed");
        if(before.assignedTo()!=null&&!before.assignedTo().equals(actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only assigned Owner may snooze");
        var dates=jdbc.query("""
            UPDATE ops_finding SET snoozed_until=now()+(? * interval '1 minute'),
              changed_at=now()
            WHERE tenant_id=? AND id=? RETURNING snoozed_until
            """,(r,i)->r.getObject(1,OffsetDateTime.class),minutes,tenant,id);
        OffsetDateTime next=dates.getFirst();
        auditAttention(tenant,id,actor,"SNOOZED",before,before.assignedTo(),next,note.strip());
        return attention(tenant,id);
    }

    @Transactional
    public Attention unsnooze(UUID tenant,UUID actor,UUID id) {
        LockRow before=lockFinding(tenant,id);
        if(before.snoozedUntil()==null)return attention(tenant,id);
        if(before.assignedTo()!=null&&!before.assignedTo().equals(actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only assigned Owner may unsnooze");
        jdbc.update("""
            UPDATE ops_finding SET snoozed_until=NULL,changed_at=now()
            WHERE tenant_id=? AND id=?
            """,tenant,id);
        auditAttention(tenant,id,actor,"UNSNOOZED",before,before.assignedTo(),null,null);
        return attention(tenant,id);
    }

    private Attention attention(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT state,assigned_to,snoozed_until FROM ops_finding
            WHERE tenant_id=? AND id=?
            """,(r,i)->new Attention(id.toString(),r.getString(1),
                r.getObject(2,UUID.class)==null?null:r.getObject(2,UUID.class).toString(),
                at(r.getObject(3,OffsetDateTime.class))),tenant,id).getFirst();
    }

    @Transactional(readOnly=true)
    public List<AttentionAudit> attentionHistory(UUID tenant,UUID id) {
        jdbc.queryForList("""
            SELECT id FROM ops_finding WHERE tenant_id=? AND id=?
            """,UUID.class,tenant,id).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return jdbc.query("""
            SELECT action,actor_user_id,old_assignee,new_assignee,
              old_snoozed_until,new_snoozed_until,note,created_at
            FROM ops_finding_attention_audit
            WHERE tenant_id=? AND finding_id=?
            ORDER BY created_at DESC,id DESC LIMIT 100
            """,(r,i)->new AttentionAudit(r.getString(1),
                r.getObject(2,UUID.class).toString(),
                r.getObject(3,UUID.class)==null?null:r.getObject(3,UUID.class).toString(),
                r.getObject(4,UUID.class)==null?null:r.getObject(4,UUID.class).toString(),
                at(r.getObject(5,OffsetDateTime.class)),at(r.getObject(6,OffsetDateTime.class)),
                r.getString(7),at(r.getObject(8,OffsetDateTime.class))),tenant,id);
    }

}
