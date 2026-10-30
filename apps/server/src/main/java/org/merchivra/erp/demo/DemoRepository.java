package org.merchivra.erp.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.demo.DemoModels.Overview;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Deliberately isolated JSONB demo storage. NOT an Amazon canonical source.
 * Snapshot writes and run status are committed atomically by DemoSyncService.
 */
@Repository
public class DemoRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DemoRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void seedIfAbsent(Overview fixture) {
        jdbc.update("""
            INSERT INTO demo_snapshot (id, payload)
            VALUES (1, CAST(? AS jsonb))
            ON CONFLICT (id) DO NOTHING
            """, toJson(fixture));
    }

    public Overview readOverview() {
        List<String> rows = jdbc.queryForList(
            "SELECT payload::text FROM demo_snapshot WHERE id = 1", String.class);
        if (rows.isEmpty()) throw new IllegalStateException("Demo snapshot not seeded");
        try {
            Overview overview = mapper.readValue(rows.getFirst(), Overview.class);
            if (!"MOCK".equals(overview.mode())) {
                throw new IllegalStateException("Demo store contains non-mock data");
            }
            return overview;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt demo snapshot", e);
        }
    }

    public void replaceOverview(Overview fixture) {
        if (!"MOCK".equals(fixture.mode())) {
            throw new IllegalArgumentException("Only invented Mock fixtures are allowed");
        }
        int changed = jdbc.update("""
            UPDATE demo_snapshot SET payload = CAST(? AS jsonb), updated_at = now() WHERE id = 1
            """, toJson(fixture));
        if (changed != 1) throw new IllegalStateException("Demo snapshot missing");
    }

    public boolean insertRun(UUID id, String key, String fingerprint, String status,
                             int count, String failureCode) {
        return jdbc.update("""
            INSERT INTO demo_sync_run
              (id, idempotency_key, request_fingerprint, status, fixture_count, failure_code)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, id, key, fingerprint, status, count, failureCode) == 1;
    }

    public boolean matchesRequest(String key, String fingerprint) {
        List<String> fingerprints = jdbc.queryForList(
            "SELECT request_fingerprint FROM demo_sync_run WHERE idempotency_key = ?",
            String.class, key);
        return fingerprints.size() == 1 && fingerprint.equals(fingerprints.getFirst());
    }

    public DemoSyncRun findByIdempotencyKey(String key) {
        return jdbc.query("""
            SELECT id, idempotency_key, request_fingerprint, status, source,
                   fixture_count, failure_code, started_at, finished_at
            FROM demo_sync_run WHERE idempotency_key = ?
            """, DemoRepository::mapRun, key).stream().findFirst().orElse(null);
    }

    public DemoSyncRun findRun(UUID id) {
        return jdbc.query("""
            SELECT id, idempotency_key, request_fingerprint, status, source,
                   fixture_count, failure_code, started_at, finished_at
            FROM demo_sync_run WHERE id = ?
            """, DemoRepository::mapRun, id).stream().findFirst().orElse(null);
    }

    public List<DemoSyncRun> recentRuns() {
        return jdbc.query("""
            SELECT id, idempotency_key, request_fingerprint, status, source,
                   fixture_count, failure_code, started_at, finished_at
            FROM demo_sync_run ORDER BY started_at DESC, id DESC LIMIT 20
            """, DemoRepository::mapRun);
    }

    private static DemoSyncRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new DemoSyncRun(
            rs.getObject("id", UUID.class).toString(),
            rs.getString("status"),
            rs.getString("source"),
            rs.getInt("fixture_count"),
            rs.getString("failure_code"),
            rs.getObject("started_at", OffsetDateTime.class).toInstant().toString(),
            rs.getObject("finished_at", OffsetDateTime.class).toInstant().toString()
        );
    }

    private String toJson(Overview fixture) {
        try {
            return mapper.writeValueAsString(fixture);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Mock fixture", e);
        }
    }
}
