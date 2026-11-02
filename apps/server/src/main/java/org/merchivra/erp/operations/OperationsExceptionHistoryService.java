package org.merchivra.erp.operations;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.merchivra.erp.fba.FbaPurchaseExceptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V81: immutable V77 audit-event analysis, not inferred Amazon operations or
 * employee performance scoring. Every query is Session-tenant + marketplace scoped.
 * History uses 30 complete-or-partial UTC calendar days ending today (UTC).
 */
@Service
public class OperationsExceptionHistoryService {
    static final int DAYS=30;
    private static final int MAX_ACTORS=100;
    private static final int MAX_RECURRENT=20;
    private final JdbcTemplate jdbc;
    private final FbaPurchaseExceptionService cases;
    public OperationsExceptionHistoryService(JdbcTemplate jdbc,
                                             FbaPurchaseExceptionService cases) {
        this.jdbc=jdbc;
        this.cases=cases;
    }
    public record Daily(String day,long registered,long resolvedActions,
        long reopenActions,long assignedActions,long acknowledgementActions,
        long evidenceRefreshActions) {}
    public record Summary(long registered,long auditedActions,long resolvedActions,
        long reopenActions,long affectedByReopen,long evidenceRefreshActions) {}
    public record Actor(String userId,String displayName,long resolvedActions,
        long reopenedActions,long assignedActions,long acknowledgedActions,
        Double meanFirstResolutionHours) {}
    public record Recurrence(String lineId,String shipmentId,
        String shipmentReference,String sellerSku,String state,
        long reopenActions,long evidenceRefreshActions,String lastReopenedAt) {}
    public record History(String marketplaceId,String fromDay,String throughDay,
        int dayCount,String timezone,String origin,boolean amazonVerified,
        boolean fbaStockCredited,Summary summary,List<Daily> daily,
        List<Actor> actors,boolean actorsTruncated,List<Recurrence> recurrences,
        boolean recurrencesTruncated) {}
    static String iso(OffsetDateTime value) {
        return value==null?null:value.toInstant().toString();
    }

    @Transactional(readOnly=true)
    public History history(UUID tenant,UUID marketplace) {
        // Reuse the existing service's tenant/marketplace authorization before
        // querying audit records, even for a marketplace with zero events.
        cases.metrics(tenant,marketplace);
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        LocalDate first=today.minusDays(DAYS-1);
        OffsetDateTime start=first.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end=today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Map<String,Daily> calendar=new HashMap<>();
        jdbc.query("""
            SELECT to_char(c.created_at AT TIME ZONE 'UTC','YYYY-MM-DD') AS day,
                   count(*) AS registered
            FROM fba_purchase_exception_case c
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
              AND c.created_at>=? AND c.created_at<?
            GROUP BY 1
            """,r->{String day=r.getString("day");
                calendar.put(day,new Daily(day,r.getLong("registered"),0,0,0,0,0));
            },tenant,marketplace,start,end);
        jdbc.query("""
            SELECT to_char(a.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD') AS day,
                count(*) FILTER (WHERE a.action='RESOLVE') AS resolved_actions,
                count(*) FILTER (WHERE a.action='REOPEN') AS reopen_actions,
                count(*) FILTER (WHERE a.action='ASSIGN') AS assigned_actions,
                count(*) FILTER (WHERE a.action='ACKNOWLEDGE') AS ack_actions,
                count(*) FILTER (WHERE a.old_fingerprint IS NOT NULL
                  AND a.old_fingerprint<>a.new_fingerprint) AS evidence_refresh_actions
            FROM fba_purchase_exception_audit a
            JOIN fba_purchase_exception_case c
              ON c.tenant_id=a.tenant_id AND c.id=a.case_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
              AND a.occurred_at>=? AND a.occurred_at<?
            GROUP BY 1
            """,r->{String day=r.getString("day");
                Daily previous=calendar.get(day);
                calendar.put(day,new Daily(day,previous==null?0:previous.registered(),
                    r.getLong("resolved_actions"),r.getLong("reopen_actions"),
                    r.getLong("assigned_actions"),r.getLong("ack_actions"),
                    r.getLong("evidence_refresh_actions")));
            },tenant,marketplace,start,end);
        List<Daily> daily=new ArrayList<>(DAYS);
        for(int i=0;i<DAYS;i++){
            String day=first.plusDays(i).toString();
            daily.add(calendar.getOrDefault(day,new Daily(day,0,0,0,0,0,0)));
        }

        long[] audit=jdbc.queryForObject("""
            SELECT count(*) AS events,
              count(*) FILTER (WHERE a.action='RESOLVE') AS resolves,
              count(*) FILTER (WHERE a.action='REOPEN') AS reopens,
              count(DISTINCT a.case_id) FILTER (WHERE a.action='REOPEN') AS affected,
              count(*) FILTER (WHERE a.old_fingerprint IS NOT NULL
                AND a.old_fingerprint<>a.new_fingerprint) AS refreshes
            FROM fba_purchase_exception_audit a
            JOIN fba_purchase_exception_case c
              ON c.tenant_id=a.tenant_id AND c.id=a.case_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
              AND a.occurred_at>=? AND a.occurred_at<?
            """,(r,i)->new long[]{r.getLong("events"),r.getLong("resolves"),
                r.getLong("reopens"),r.getLong("affected"),r.getLong("refreshes")},
            tenant,marketplace,start,end);
        long registered=daily.stream().mapToLong(Daily::registered).sum();
        Summary summary=new Summary(registered,audit[0],audit[1],audit[2],audit[3],audit[4]);

        // Actor activity is NOT attributed assignee workload. First-resolution
        // latency is measured once per case from local case registration, not
        // from Amazon delivery and not from every RESOLVE after a reopen.
        List<Actor> actors=jdbc.query("""
            SELECT a.actor_user_id,u.identity_ref,
              count(*) FILTER (WHERE a.action='RESOLVE') AS resolves,
              count(*) FILTER (WHERE a.action='REOPEN') AS reopens,
              count(*) FILTER (WHERE a.action='ASSIGN') AS assignments,
              count(*) FILTER (WHERE a.action='ACKNOWLEDGE') AS acknowledges,
              round(avg(extract(epoch FROM (a.occurred_at-c.created_at))/3600)
                  FILTER (WHERE a.action='RESOLVE' AND NOT EXISTS(
                    SELECT 1 FROM fba_purchase_exception_audit earlier
                    WHERE earlier.tenant_id=a.tenant_id
                      AND earlier.case_id=a.case_id AND earlier.action='RESOLVE'
                      AND (earlier.occurred_at,earlier.id)
                          <(a.occurred_at,a.id)
                  )),1) AS first_resolve_hours
            FROM fba_purchase_exception_audit a
            JOIN fba_purchase_exception_case c
              ON c.tenant_id=a.tenant_id AND c.id=a.case_id
            JOIN app_user u ON u.id=a.actor_user_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
              AND a.occurred_at>=? AND a.occurred_at<?
            GROUP BY a.actor_user_id,u.identity_ref
            ORDER BY resolves DESC, a.actor_user_id
            LIMIT ?
            """,(r,i)->{
                var latency=r.getBigDecimal("first_resolve_hours");
                return new Actor(r.getObject("actor_user_id",UUID.class).toString(),
                    r.getString("identity_ref"),r.getLong("resolves"),
                    r.getLong("reopens"),r.getLong("assignments"),
                    r.getLong("acknowledges"),
                    latency==null?null:latency.doubleValue());
            },tenant,marketplace,start,end,MAX_ACTORS+1);
        boolean actorsTruncated=actors.size()>MAX_ACTORS;
        if(actorsTruncated)actors=actors.subList(0,MAX_ACTORS);

        // Recurrence is OBSERVED REOPEN or fingerprint transition in V77 audit,
        // never guesses about source changes before a human action recorded them.
        List<Recurrence> recurrence=jdbc.query("""
            SELECT c.inbound_line_id,c.shipment_id,s.reference,
                   l.seller_sku,c.state,
                   count(*) FILTER (WHERE a.action='REOPEN') AS reopens,
                   count(*) FILTER (WHERE a.old_fingerprint IS NOT NULL
                       AND a.old_fingerprint<>a.new_fingerprint) AS refreshed,
                   max(a.occurred_at) FILTER (WHERE a.action='REOPEN') AS last_reopened
            FROM fba_purchase_exception_audit a
            JOIN fba_purchase_exception_case c
              ON c.tenant_id=a.tenant_id AND c.id=a.case_id
            JOIN fba_inbound_shipment s
              ON s.tenant_id=c.tenant_id AND s.id=c.shipment_id
              AND s.seller_marketplace_id=c.seller_marketplace_id
            JOIN fba_inbound_line l
              ON l.tenant_id=c.tenant_id AND l.shipment_id=c.shipment_id
              AND l.id=c.inbound_line_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=?
              AND a.occurred_at>=? AND a.occurred_at<?
            GROUP BY c.inbound_line_id,c.shipment_id,s.reference,l.seller_sku,c.state
            HAVING count(*) FILTER (WHERE a.action='REOPEN')>0
                OR count(*) FILTER (WHERE a.old_fingerprint IS NOT NULL
                    AND a.old_fingerprint<>a.new_fingerprint)>0
            ORDER BY reopens DESC,refreshed DESC,c.inbound_line_id
            LIMIT ?
            """,(r,i)->new Recurrence(
                r.getObject("inbound_line_id",UUID.class).toString(),
                r.getObject("shipment_id",UUID.class).toString(),
                r.getString("reference"),r.getString("seller_sku"),
                r.getString("state"),r.getLong("reopens"),r.getLong("refreshed"),
                iso(r.getObject("last_reopened",OffsetDateTime.class))),
            tenant,marketplace,start,end,MAX_RECURRENT+1);
        boolean recurrencesTruncated=recurrence.size()>MAX_RECURRENT;
        if(recurrencesTruncated)recurrence=recurrence.subList(0,MAX_RECURRENT);
        return new History(marketplace.toString(),first.toString(),
            today.toString(),DAYS,"UTC","LOCAL_AUDIT",false,false,
            summary,daily,actors,actorsTruncated,recurrence,recurrencesTruncated);
    }
}
