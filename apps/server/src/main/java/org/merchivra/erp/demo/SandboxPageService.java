package org.merchivra.erp.demo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit one-page-per-request Mock checkpoint protocol. Each HTTP invocation
 * is one PostgreSQL transaction, so a committed page survives process restarts.
 * SELECT FOR UPDATE NOWAIT serializes a stream and lease_epoch fences updates.
 * This is NOT a real SP-API Worker: no API token expiry, lease TTL or scheduler.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.demo", name="enabled", havingValue="true")
public class SandboxPageService {
    private final JdbcTemplate jdbc;
    private final MockSpApiClient source;
    private final SandboxSyncService facts;

    public SandboxPageService(JdbcTemplate jdbc, MockSpApiClient source, SandboxSyncService facts) {
        this.jdbc = jdbc; this.source = source; this.facts = facts;
    }

    public record Step(String status, int committedPage, int nextPage, int totalPages,
                       int recordCount, long leaseEpoch, boolean replayed) {}
    public record Progress(String status, int nextPage, int totalPages, long leaseEpoch,
                           List<Step> pages) {}
    private record Cursor(int nextPage, int totalPages, long epoch) {}

    @Transactional
    public Step advance(UUID tenant, UUID actor, UUID marketplace, String requestKey) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        String site = facts.localSite(tenant, marketplace);
        var fixture = simulated(site);
        int total = fixture.orderPages().size();
        if (total < 1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "No Mock Orders pages");

        jdbc.update("""
            INSERT INTO sandbox_page_stream
              (tenant_id, seller_marketplace_id, total_pages)
            VALUES (?, ?, ?)
            ON CONFLICT (tenant_id, seller_marketplace_id) DO NOTHING
            """, tenant, marketplace, total);

        // NOWAIT fails immediately instead of queuing another ingestion request.
        // A caller can safely retry with the *same* Idempotency-Key.
        Cursor cursor = jdbc.query("""
            SELECT next_page, total_pages, lease_epoch
            FROM sandbox_page_stream
            WHERE tenant_id = ? AND seller_marketplace_id = ?
            FOR UPDATE NOWAIT
            """, (rs, row) -> new Cursor(rs.getInt("next_page"),
                rs.getInt("total_pages"), rs.getLong("lease_epoch")),
            tenant, marketplace).getFirst();

        // The idempotency decision MUST happen under the locked stream row,
        // otherwise concurrent retries can write or advance twice.
        List<Step> previous = jdbc.query("""
            SELECT page_index, total_pages, record_count, lease_epoch
            FROM sandbox_page_commit
            WHERE tenant_id = ? AND seller_marketplace_id = ? AND idempotency_key = ?
            """, (rs, row) -> new Step(
                rs.getInt("page_index") + 1 == rs.getInt("total_pages") ? "SUCCEEDED" : "RUNNING",
                rs.getInt("page_index"), rs.getInt("page_index") + 1,
                rs.getInt("total_pages"), rs.getInt("record_count"),
                rs.getLong("lease_epoch"), true),
            tenant, marketplace, requestKey);
        if (!previous.isEmpty()) return previous.getFirst();
        if (cursor.totalPages() != total) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Mock fixture page count changed; reset requires a new stream version");
        }
        if (cursor.nextPage() >= total) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Mock stream already completed");
        }

        int index = cursor.nextPage();
        // The first page creates Listing mappings; last page creates FBA
        // observations. All three fact families are committed by final page.
        if (index == 0) {
            for (var listing : fixture.listings()) facts.upsertListing(tenant, marketplace, listing);
        }
        var page = fixture.orderPages().get(index);
        for (var order : page.records()) facts.upsertOrder(tenant, marketplace, order);
        if (index + 1 == total) {
            for (var item : fixture.inventory()) facts.upsertInventory(tenant, marketplace, item);
            jdbc.update("""
                INSERT INTO local_setup_audit
                  (id, tenant_id, actor_user_id, action, resource_type, resource_id)
                VALUES (?, ?, ?, 'SANDBOX_FIXTURE_IMPORTED', 'MARKETPLACE', ?)
                """, UUID.randomUUID(), tenant, actor, marketplace);
        }
        long epoch = cursor.epoch() + 1;
        jdbc.update("""
            INSERT INTO sandbox_page_commit
              (id, tenant_id, seller_marketplace_id, idempotency_key,
               page_index, total_pages, record_count, lease_epoch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), tenant, marketplace, requestKey,
            index, total, page.records().size(), epoch);
        int updated = jdbc.update("""
            UPDATE sandbox_page_stream
            SET next_page = ?, lease_epoch = ?, updated_at = now(),
                completed_at = CASE WHEN ? = total_pages THEN now() ELSE NULL END
            WHERE tenant_id = ? AND seller_marketplace_id = ?
              AND next_page = ? AND lease_epoch = ?
            """, index + 1, epoch, index + 1, tenant, marketplace,
            index, cursor.epoch());
        if (updated != 1) throw new IllegalStateException("Stale Mock stream fencing epoch");
        return new Step(index + 1 == total ? "SUCCEEDED" : "RUNNING",
            index, index + 1, total, page.records().size(), epoch, false);
    }

    @Transactional(readOnly = true)
    public Progress progress(UUID tenant, UUID marketplace) {
        String site = facts.localSite(tenant, marketplace);
        var fixture = simulated(site);
        List<Cursor> cursors = jdbc.query("""
            SELECT next_page, total_pages, lease_epoch FROM sandbox_page_stream
            WHERE tenant_id = ? AND seller_marketplace_id = ?
            """, (rs, row) -> new Cursor(rs.getInt("next_page"),
                rs.getInt("total_pages"), rs.getLong("lease_epoch")), tenant, marketplace);
        if (cursors.isEmpty()) return new Progress("NOT_STARTED", 0,
            fixture.orderPages().size(), 0L, List.of());
        Cursor cursor = cursors.getFirst();
        List<Step> steps = jdbc.query("""
            SELECT page_index, total_pages, record_count, lease_epoch
            FROM sandbox_page_commit
            WHERE tenant_id = ? AND seller_marketplace_id = ?
            ORDER BY page_index
            """, (rs, row) -> new Step(
                rs.getInt("page_index") + 1 == rs.getInt("total_pages") ? "SUCCEEDED" : "RUNNING",
                rs.getInt("page_index"), rs.getInt("page_index") + 1,
                rs.getInt("total_pages"), rs.getInt("record_count"),
                rs.getLong("lease_epoch"), false), tenant, marketplace);
        return new Progress(cursor.nextPage() == cursor.totalPages() ? "SUCCEEDED" : "RUNNING",
            cursor.nextPage(), cursor.totalPages(), cursor.epoch(), steps);
    }

    private MockSpApiClient.Snapshot simulated(String site) {
        try { return source.simulate(site); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No fictional scenario for Marketplace");
        }
    }
}
