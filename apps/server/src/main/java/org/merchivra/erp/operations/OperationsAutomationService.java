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
                          String lastSeenAt) {}
    public record ScanResult(String id, String triggerType, int matchedCount,
                             int clearedCount) {}
    public record Summary(long open, long acknowledged, long done, long cleared) {}
    public record FindingChange(String id, String state) {}

    @Transactional(readOnly = true)
    public List<Finding> findings(UUID tenant, String filter) {
        if (!"ACTIVE".equals(filter) && !"ALL".equals(filter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filter must be ACTIVE or ALL");
        }
        return jdbc.query("""
            SELECT id, seller_marketplace_id, rule_code, source_key, title,
                   severity, state, data_origin, last_seen_at
            FROM ops_finding
            WHERE tenant_id = ? AND (? = 'ALL' OR state IN ('OPEN','ACKNOWLEDGED'))
            ORDER BY CASE state WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1
                                WHEN 'DONE' THEN 2 ELSE 3 END,
                     changed_at DESC, id DESC
            LIMIT 100
            """, OperationsAutomationService::mapFinding, tenant, filter);
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID tenant) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE state = 'OPEN') AS open_count,
                   COUNT(*) FILTER (WHERE state = 'ACKNOWLEDGED') AS acknowledged_count,
                   COUNT(*) FILTER (WHERE state = 'DONE') AS done_count,
                   COUNT(*) FILTER (WHERE state = 'CLEARED') AS cleared_count
            FROM ops_finding WHERE tenant_id = ?
            """, (rs, row) -> new Summary(rs.getLong("open_count"),
                rs.getLong("acknowledged_count"), rs.getLong("done_count"),
                rs.getLong("cleared_count")), tenant);
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
            seen.toInstant().toString());
    }

    private static final String UPSERT = """
        ON CONFLICT (tenant_id, seller_marketplace_id, rule_code, source_key)
        DO UPDATE SET last_scan_id = EXCLUDED.last_scan_id,
                      last_seen_at = now(),
                      severity = EXCLUDED.severity,
                      data_origin = EXCLUDED.data_origin,
                      state = CASE WHEN ops_finding.state = 'CLEARED' THEN 'OPEN'
                                   ELSE ops_finding.state END,
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
}
