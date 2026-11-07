package org.merchivra.erp.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import org.merchivra.erp.purchasing.LocalKeysetPage;
import java.util.ArrayList;
import java.util.Collections;
import org.merchivra.erp.fba.FbaPurchaseExceptionService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final FbaPurchaseExceptionService fbaExceptions;

    // Kept for isolated legacy unit tests; production constructor is Spring-injected.
    public OperationsAutomationService(JdbcTemplate jdbc,int threshold) {
        this(jdbc,threshold,null);
    }
    @Autowired
    public OperationsAutomationService(JdbcTemplate jdbc,
        @Value("${merchivra.operations.low-stock-threshold:3}") int lowStockThreshold,
        FbaPurchaseExceptionService fbaExceptions) {
        if (lowStockThreshold < 0 || lowStockThreshold > 10000) {
            throw new IllegalArgumentException("Low stock threshold must be 0..10000");
        }
        this.jdbc = jdbc;
        this.lowStockThreshold = lowStockThreshold;
        this.fbaExceptions = fbaExceptions;
    }

    public record Finding(String id, String marketplaceId, String ruleCode, String sourceKey,
                          String title, String severity, String state, String dataOrigin,
                          String lastSeenAt,String assignedTo,String snoozedUntil) {}
    private record RankedFinding(Finding item,int rank,String changedAt) {}
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
    private record StoredRule(boolean enabled,Integer threshold,long version) {}
    private static final List<String> RULES=List.of(
        "UNMAPPED_LISTING","INVENTORY_UNKNOWN","LOW_STOCK",
        "REFUND_REVIEW","FBA_INBOUND_REVIEW","ADS_REPORT_REVIEW",
        "FBA_PO_EVIDENCE_CHANGED","FBA_PO_EXCEPTION_OVERDUE");
    public record RuleSetting(String ruleCode,boolean enabled,Integer lowStockThreshold,
                              long version,String updatedAt) {}
    public record SaveRule(@jakarta.validation.constraints.NotNull Boolean enabled,
                           Integer lowStockThreshold,
                           @jakarta.validation.constraints.NotNull
                           @jakarta.validation.constraints.Min(0) Long expectedVersion) {}
    public record RuleAudit(String ruleCode,String actorId,Boolean oldEnabled,
                            boolean newEnabled,Integer oldThreshold,Integer newThreshold,
                            Long oldVersion,long newVersion,String createdAt) {}
    private static void checkRule(String code) {
        if(!RULES.contains(code))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported operation rule");
    }
    private void requireMarketplace(UUID tenant,UUID marketplace) {
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace m JOIN tenant t
             ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,Integer.class,tenant,marketplace);
        if(count==null||count!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Marketplace not found");
    }
    @Transactional(readOnly=true)
    public List<RuleSetting> rules(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            WITH codes(rule_code) AS (VALUES
             ('UNMAPPED_LISTING'),('INVENTORY_UNKNOWN'),('LOW_STOCK'),
             ('REFUND_REVIEW'),('FBA_INBOUND_REVIEW'),('ADS_REPORT_REVIEW'),
             ('FBA_PO_EVIDENCE_CHANGED'),('FBA_PO_EXCEPTION_OVERDUE'))
            SELECT codes.rule_code, COALESCE(r.enabled,TRUE) AS enabled,
              CASE WHEN codes.rule_code='LOW_STOCK'
                   THEN COALESCE(r.low_stock_threshold,?)
                   ELSE NULL END AS low_stock_threshold,
              COALESCE(r.version,0) AS version,r.updated_at
            FROM codes LEFT JOIN ops_rule_setting r ON r.tenant_id=?
             AND r.seller_marketplace_id=? AND r.rule_code=codes.rule_code
            ORDER BY codes.rule_code
            """,(rs,i)->new RuleSetting(rs.getString("rule_code"),rs.getBoolean("enabled"),
                rs.getObject("low_stock_threshold",Integer.class),rs.getLong("version"),
                at(rs.getObject("updated_at",OffsetDateTime.class))),
            lowStockThreshold,tenant,marketplace);
    }
    @Transactional
    public RuleSetting saveRule(UUID tenant,UUID actor,UUID marketplace,
        String code,SaveRule input) {
        checkRule(code);
        if(input==null||input.enabled()==null||input.expectedVersion()==null||input.expectedVersion()<0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected version must be nonnegative");
        if("LOW_STOCK".equals(code) && (input.lowStockThreshold()==null
            ||input.lowStockThreshold()<0||input.lowStockThreshold()>10000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Low stock threshold must be 0..10000");
        if(!"LOW_STOCK".equals(code)&&input.lowStockThreshold()!=null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Threshold applies only to LOW_STOCK");
        // Same tenant lock used by scanning; changes cannot interleave with an
        // in-progress scan or another Owner's write across server replicas.
        var locked=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(locked.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tenant inactive");
        requireMarketplace(tenant,marketplace);
        var rows=jdbc.query("""
            SELECT enabled,low_stock_threshold,version
            FROM ops_rule_setting
            WHERE tenant_id=? AND seller_marketplace_id=? AND rule_code=? FOR UPDATE
            """,(rs,i)->new StoredRule(rs.getBoolean(1),rs.getObject(2,Integer.class),
                rs.getLong(3)),tenant,marketplace,code);
        StoredRule previous=rows.isEmpty()?null:rows.getFirst();
        long current=previous==null?0:previous.version();
        if(input.expectedVersion()!=current)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Rule modified, reload before retry");
        Integer threshold="LOW_STOCK".equals(code)?input.lowStockThreshold():null;
        if(previous!=null && previous.enabled()==input.enabled()
            && java.util.Objects.equals(previous.threshold(),threshold))
            return new RuleSetting(code,previous.enabled(),previous.threshold(),current,
                jdbc.queryForObject("""
                    SELECT updated_at::text FROM ops_rule_setting
                    WHERE tenant_id=? AND seller_marketplace_id=? AND rule_code=?
                    """,String.class,tenant,marketplace,code));
        long next=current+1;
        jdbc.update("""
            INSERT INTO ops_rule_setting
             (tenant_id,seller_marketplace_id,rule_code,enabled,low_stock_threshold,
              version,updated_by)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,seller_marketplace_id,rule_code)
            DO UPDATE SET enabled=EXCLUDED.enabled,
              low_stock_threshold=EXCLUDED.low_stock_threshold,
              version=EXCLUDED.version,updated_by=EXCLUDED.updated_by,updated_at=now()
            """,tenant,marketplace,code,input.enabled(),threshold,next,actor);
        jdbc.update("""
            INSERT INTO ops_rule_setting_audit
              (id,tenant_id,seller_marketplace_id,rule_code,actor_user_id,
               old_enabled,new_enabled,old_threshold,new_threshold,old_version,new_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,marketplace,code,actor,
            previous==null?null:previous.enabled(),input.enabled(),
            previous==null?null:previous.threshold(),threshold,
            previous==null?null:previous.version(),next);
        return jdbc.query("""
            SELECT enabled,low_stock_threshold,version,updated_at
            FROM ops_rule_setting WHERE tenant_id=? AND seller_marketplace_id=?
              AND rule_code=?
            """,(rs,i)->new RuleSetting(code,rs.getBoolean(1),
                rs.getObject(2,Integer.class),rs.getLong(3),at(rs.getObject(4,OffsetDateTime.class))),
            tenant,marketplace,code).getFirst();
    }
    @Transactional(readOnly=true)
    public List<RuleAudit> ruleHistory(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            SELECT rule_code,actor_user_id,old_enabled,new_enabled,old_threshold,
              new_threshold,old_version,new_version,created_at
            FROM ops_rule_setting_audit
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY created_at DESC,id DESC LIMIT 100
            """,(rs,i)->new RuleAudit(rs.getString(1),rs.getObject(2,UUID.class).toString(),
                rs.getObject(3,Boolean.class),rs.getBoolean(4),
                rs.getObject(5,Integer.class),rs.getObject(6,Integer.class),
                rs.getObject(7,Long.class),rs.getLong(8),
                at(rs.getObject(9,OffsetDateTime.class))),tenant,marketplace);
    }


    @Transactional(readOnly = true)
    public List<Finding> findings(UUID tenant,String filter) {
        return findings(tenant,filter,null);
    }

    /** V85: exclude FBA exception findings from explicitly VIEW-denied
     * Marketplace profiles BEFORE LIMIT 100; preserve non-FBA Ops findings.
     * A null actor is reserved for legacy internal calls, never HTTP.
     */
    @Transactional(readOnly = true)
    public List<Finding> findings(UUID tenant, String filter,UUID actor) {
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
              AND (?::uuid IS NULL OR NOT (
                  rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE')
                  AND EXISTS (
                      SELECT 1 FROM fba_exception_owner_permission p
                      WHERE p.tenant_id=ops_finding.tenant_id
                        AND p.seller_marketplace_id=ops_finding.seller_marketplace_id
                        AND p.owner_user_id=?::uuid AND NOT p.can_view
                  )))
            ORDER BY CASE state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                                WHEN 'DONE' THEN 2 ELSE 3 END,
                     changed_at DESC, id DESC
            LIMIT 100
            """, OperationsAutomationService::mapFinding, tenant, filter, filter, actor,actor);
    }

    /** V95: tenant/actor/filter-scoped cursor, full queue (not LIMIT 100).
     * VIEW-denied FBA findings are removed by SQL BEFORE pagination.
     * State priority is preserved across page boundaries; changing findings
     * can reorder a live queue, so this is deliberately not a snapshot.
     */
    @Transactional(readOnly = true)
    public LocalKeysetPage.Page<Finding> findingsPage(UUID tenant,String filter,
        UUID actor,int requested,String cursor) {
        if (!List.of("ACTIVE","ACTIONABLE","ALL").contains(filter))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "filter must be ACTIVE, ACTIONABLE or ALL");
        if(actor==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Authenticated Owner required");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,6);
        if(after!=null && (!tenant.toString().equals(after[0])
            || !actor.toString().equals(after[1]) || !filter.equals(after[2])))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Operations cursor belongs to another tenant, Owner or filter");
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(filter);args.add(filter);args.add(actor);args.add(actor);
        String where="";
        if(after!=null) {
            try {
                int rank=Integer.parseInt(after[3]);
                if(rank<0||rank>3)throw new IllegalArgumentException();
                OffsetDateTime time=OffsetDateTime.ofInstant(Instant.parse(after[4]),ZoneOffset.UTC);
                where=" WHERE (state_rank > ? OR (state_rank = ? AND (changed_at,id) < (?,?)))";
                args.add(rank);args.add(rank);args.add(time);args.add(UUID.fromString(after[5]));
            } catch(Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid Operations cursor");
            }
        }
        args.add(limit+1);
        List<RankedFinding> rows=jdbc.query("""
            WITH visible AS (
              SELECT id,seller_marketplace_id,rule_code,source_key,title,
                     severity,state,data_origin,last_seen_at,assigned_to,snoozed_until,
                     changed_at,
                     CASE state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                                WHEN 'DONE' THEN 2 ELSE 3 END AS state_rank
              FROM ops_finding
              WHERE tenant_id=?
                AND (?='ALL' OR state IN ('OPEN','ACKNOWLEDGED'))
                AND (?<>'ACTIONABLE' OR snoozed_until IS NULL OR snoozed_until<=now())
                AND (?::uuid IS NULL OR NOT (
                    rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE')
                    AND EXISTS (
                      SELECT 1 FROM fba_exception_owner_permission p
                      WHERE p.tenant_id=ops_finding.tenant_id
                        AND p.seller_marketplace_id=ops_finding.seller_marketplace_id
                        AND p.owner_user_id=?::uuid AND NOT p.can_view
                    )))
            )
            SELECT * FROM visible
            """+where+" ORDER BY state_rank ASC,changed_at DESC,id DESC LIMIT ?",
            (rs,i)->new RankedFinding(mapFinding(rs,i),
                rs.getInt("state_rank"),
                rs.getObject("changed_at",OffsetDateTime.class).toInstant().toString()),
            args.toArray());
        var page=LocalKeysetPage.pack(rows,limit,r->LocalKeysetPage.encode(
            tenant.toString(),actor.toString(),filter,String.valueOf(r.rank()),
            r.changedAt(),r.item().id()));
        return new LocalKeysetPage.Page<>(
            page.items().stream().map(RankedFinding::item).toList(),
            page.nextCursor(),page.hasMore(),page.pageSize());
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
        if(fbaExceptions!=null)matched += scanFbaSignals(tenant,scanId);

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
         AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
          WHERE config.tenant_id=l.tenant_id
            AND config.seller_marketplace_id=l.seller_marketplace_id
            AND config.rule_code='UNMAPPED_LISTING' AND NOT config.enabled)
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
        WHERE (latest.quality IN ('UNKNOWN','STALE','PARTIAL')
              OR latest.fulfillable IS NULL)
         AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
          WHERE config.tenant_id=latest.tenant_id
            AND config.seller_marketplace_id=latest.seller_marketplace_id
            AND config.rule_code='INVENTORY_UNKNOWN' AND NOT config.enabled)
        """ + UPSERT;

    private static final String LOW_STOCK_SQL = """
        INSERT INTO ops_finding (id, tenant_id, seller_marketplace_id, rule_code,
                                 source_key, title, severity, data_origin, last_scan_id)
        SELECT gen_random_uuid(), latest.tenant_id, latest.seller_marketplace_id,
               'LOW_STOCK', latest.seller_sku_raw, 'FBA 可售库存偏低',
               'CRITICAL', latest.data_origin, ?
        """ + INVENTORY_SOURCE + """
        WHERE latest.quality = 'OBSERVED' AND latest.fulfillable IS NOT NULL
          AND latest.fulfillable <= COALESCE((
              SELECT config.low_stock_threshold FROM ops_rule_setting config
              WHERE config.tenant_id=latest.tenant_id
                AND config.seller_marketplace_id=latest.seller_marketplace_id
                AND config.rule_code='LOW_STOCK'),?)
         AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
          WHERE config.tenant_id=latest.tenant_id
            AND config.seller_marketplace_id=latest.seller_marketplace_id
            AND config.rule_code='LOW_STOCK' AND NOT config.enabled)
        """ + UPSERT;

    private static final String REFUNDS_SQL = """
        INSERT INTO ops_finding (id, tenant_id, seller_marketplace_id, rule_code,
                                 source_key, title, severity, data_origin, last_scan_id)
        SELECT gen_random_uuid(), o.tenant_id, o.seller_marketplace_id,
               'REFUND_REVIEW', o.id::text, '退款订单待核对',
               'INFO', o.data_origin, ?
        FROM sales_order o
        WHERE o.tenant_id = ? AND o.refund_status IN ('PARTIAL','FULL')
         AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
          WHERE config.tenant_id=o.tenant_id
            AND config.seller_marketplace_id=o.seller_marketplace_id
            AND config.rule_code='REFUND_REVIEW' AND NOT config.enabled)
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
         AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
          WHERE config.tenant_id=s.tenant_id
            AND config.seller_marketplace_id=s.seller_marketplace_id
            AND config.rule_code='FBA_INBOUND_REVIEW' AND NOT config.enabled)
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
         AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
          WHERE config.tenant_id=r.tenant_id
            AND config.seller_marketplace_id=r.seller_marketplace_id
            AND config.rule_code='ADS_REPORT_REVIEW' AND NOT config.enabled)
        """ + UPSERT;

    /** V78: the existing V10 inbox consumes V77's bounded evidence projection.
     * Nothing touches PO, ASN, inventory or Amazon, and no alternate inbox exists.
     * Unobserved historical cases are retained rather than spuriously cleared.
     */
    private int scanFbaSignals(UUID tenant,UUID scanId) {
        int matched=0;
        List<UUID> marketplaces=jdbc.queryForList("""
            SELECT DISTINCT seller_marketplace_id FROM fba_purchase_exception_case
            WHERE tenant_id=? ORDER BY seller_marketplace_id
            """,UUID.class,tenant);
        for(UUID marketplace:marketplaces) {
            var board=fbaExceptions.board(tenant,marketplace);
            List<String> ids=board.items().stream().map(
                FbaPurchaseExceptionService.WorkItem::lineId).toList();
            // Preserve queue entries for cases outside the bounded 100 shipment /
            // 500 line evidence view, even if the view is truncated today.
            String excluded=ids.isEmpty()?"":" AND source_key NOT IN ("+
                String.join(",",Collections.nCopies(ids.size(),"?"))+")";
            List<Object> keepArgs=new ArrayList<>(List.of(scanId,tenant,marketplace));
            keepArgs.addAll(ids);
            jdbc.update("""
                UPDATE ops_finding SET last_scan_id=?
                WHERE tenant_id=? AND seller_marketplace_id=?
                AND rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE')
                """+excluded,keepArgs.toArray());
            if(board.truncated()) {
                jdbc.update("""
                    UPDATE ops_finding SET last_scan_id=?
                    WHERE tenant_id=? AND seller_marketplace_id=?
                      AND rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE')
                    """,scanId,tenant,marketplace);
                continue; // partial source evidence cannot safely clear prior signals
            }
            List<String> changed=board.items().stream().filter(
                FbaPurchaseExceptionService.WorkItem::stale).map(
                FbaPurchaseExceptionService.WorkItem::lineId).toList();
            List<String> overdue=board.policy().enabled()?board.items().stream()
                .filter(x->"OVERDUE".equals(x.reminder())).map(
                FbaPurchaseExceptionService.WorkItem::lineId).toList():List.of();
            matched+=upsertFbaSignals(tenant,marketplace,scanId,changed,
                "FBA_PO_EVIDENCE_CHANGED","采购/FBA 本地证据变化待重新确认","WARNING");
            matched+=upsertFbaSignals(tenant,marketplace,scanId,overdue,
                "FBA_PO_EXCEPTION_OVERDUE","采购/FBA 人工处理期限已超期","CRITICAL");
        }
        return matched;
    }
    private int upsertFbaSignals(UUID tenant,UUID marketplace,UUID scanId,
                                  List<String> lines,String rule,String title,String severity) {
        if(lines.isEmpty())return 0;
        String marks=String.join(",",Collections.nCopies(lines.size(),"?"));
        String sql="""
            INSERT INTO ops_finding
             (id,tenant_id,seller_marketplace_id,rule_code,source_key,title,
              severity,data_origin,last_scan_id)
            SELECT gen_random_uuid(),c.tenant_id,c.seller_marketplace_id,?,
              c.inbound_line_id::text,?,?,
              CASE WHEN s.evidence_origin='MOCK' THEN 'MOCK'
                   ELSE 'UNVERIFIED' END,?
            FROM fba_purchase_exception_case c
            JOIN fba_inbound_shipment s ON s.tenant_id=c.tenant_id AND s.id=c.shipment_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
              AND c.inbound_line_id IN ("""+marks+"""
            )
              AND NOT EXISTS (SELECT 1 FROM ops_rule_setting config
                WHERE config.tenant_id=c.tenant_id
                  AND config.seller_marketplace_id=c.seller_marketplace_id
                  AND config.rule_code=? AND NOT config.enabled)
            """+UPSERT;
        List<Object> args=new ArrayList<>(List.of(rule,title,severity,scanId,tenant,marketplace));
        for(String line:lines)args.add(UUID.fromString(line));
        args.add(rule);
        return jdbc.update(sql,args.toArray());
    }

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
