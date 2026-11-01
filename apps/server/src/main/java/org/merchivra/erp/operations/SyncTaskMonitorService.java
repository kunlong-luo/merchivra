package org.merchivra.erp.operations;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * V57 read-only task observability. SQL is always scoped to the session tenant.
 * Never select pagination cursors, lease owners, Seller IDs or upstream payloads.
 * This endpoint is for tenant Owners, not global cluster-wide operations.
 */
@Service
public class SyncTaskMonitorService {
    private final JdbcTemplate jdbc;

    public SyncTaskMonitorService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Task(UUID jobId, UUID marketplaceId, String kind, String origin,
        String state, int committedPages, int retryCount, String nextAttemptAt,
        String updatedAt, String lastFailureCode, boolean leaseExpired) { }

    public record Summary(long total, long ready, long running, long retrying,
        long paused, long succeeded) { }

    public record Snapshot(Summary summary, List<Task> tasks, int limit,
        boolean hasMore) { }

    // The same tenant predicate is applied independently to all three durable
    // job tables. No caller-supplied SQL identifiers or tenant parameters.
    static final String JOBS = """
        WITH jobs AS (
          SELECT id AS job_id, seller_marketplace_id AS marketplace_id,
                 'ORDERS'::text AS kind, mode::text AS origin,
                 state::text AS state, page_index AS pages,
                 retry_count, next_attempt_at AS retry_at, updated_at,
                 last_failure_code,
                 (lease_expires_at IS NOT NULL AND lease_expires_at<=now()) AS lease_expired
          FROM orders_sync_job WHERE tenant_id=?
          UNION ALL
          SELECT id, seller_marketplace_id, 'CATALOG_' || stream, 'LIVE',
                 state, page_index, retry_count, retry_at, updated_at,
                 last_failure_code,
                 (lease_expires_at IS NOT NULL AND lease_expires_at<=now())
          FROM spapi_catalog_sync_job WHERE tenant_id=?
          UNION ALL
          SELECT id, seller_marketplace_id, 'FINANCES', 'LIVE',
                 state, page_index, retry_count, retry_at, updated_at,
                 last_failure_code,
                 (lease_expires_at IS NOT NULL AND lease_expires_at<=now())
          FROM spapi_finances_sync_job WHERE tenant_id=?
        )
        """;

    public Snapshot snapshot(UUID tenant, String kind, String state, int limit) {
        if (tenant == null) throw new SecurityException("Session tenant required");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be 1..100");
        String k = kind == null ? "ALL" : kind;
        String s = state == null ? "ALL" : state;
        if (!List.of("ALL","ORDERS","CATALOG_LISTINGS","CATALOG_FBA","FINANCES").contains(k))
            throw new IllegalArgumentException("Unknown sync task kind");
        if (!List.of("ALL","READY","RUNNING","RETRY","WAITING_RETRY","PAUSED","SUCCEEDED").contains(s))
            throw new IllegalArgumentException("Unknown sync task state");

        Summary summary=jdbc.query(JOBS + """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE state='READY') AS ready,
                   count(*) FILTER (WHERE state='RUNNING') AS running,
                   count(*) FILTER (WHERE state IN ('RETRY','WAITING_RETRY')) AS retrying,
                   count(*) FILTER (WHERE state='PAUSED') AS paused,
                   count(*) FILTER (WHERE state='SUCCEEDED') AS succeeded
            FROM jobs
            """,(rs,n)->new Summary(rs.getLong("total"),rs.getLong("ready"),
                rs.getLong("running"),rs.getLong("retrying"),rs.getLong("paused"),
                rs.getLong("succeeded")),tenant,tenant,tenant).getFirst();

        List<Task> rows=jdbc.query(JOBS + """
            SELECT job_id, marketplace_id, kind, origin, state, pages,
                   retry_count, retry_at, updated_at, last_failure_code, lease_expired
            FROM jobs
            WHERE (?='ALL' OR kind=?)
              AND (?='ALL' OR state=?)
            ORDER BY updated_at DESC, job_id
            LIMIT ?
            """,(rs,n)->new Task(rs.getObject("job_id",UUID.class),
                rs.getObject("marketplace_id",UUID.class),
                rs.getString("kind"),rs.getString("origin"),rs.getString("state"),
                rs.getInt("pages"),rs.getInt("retry_count"),
                utc(rs.getObject("retry_at",OffsetDateTime.class)),
                utc(rs.getObject("updated_at",OffsetDateTime.class)),
                rs.getString("last_failure_code"),rs.getBoolean("lease_expired")),
            tenant,tenant,tenant,k,k,s,s,limit+1);
        boolean more=rows.size()>limit;
        return new Snapshot(summary,more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows),
            limit,more);
    }

    private static String utc(OffsetDateTime date) {
        return date==null ? null : date.toInstant().toString();
    }
}
