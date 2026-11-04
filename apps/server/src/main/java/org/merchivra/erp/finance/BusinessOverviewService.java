package org.merchivra.erp.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V89: bounded, tenant-scoped operational quality snapshot. Counts are from
 * canonical tables, never the Demo Gateway or the first 50 UI rows.
 * Money, if present, is a reference to the already frozen V64 extraction,
 * never recomputed or aggregated across Marketplace/currency.
 */
@Service
public class BusinessOverviewService {
    private final JdbcTemplate jdbc;
    public BusinessOverviewService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Sources(long mock,long unverified,long live) {}
    public record Listings(long total,long unmapped,long conflicts,
                           long unknownSourceTime,Sources sources) {}
    public record Orders(long total,long itemsNotObserved,long financeNotObserved,
                         long unknownRefund,long unknownSourceTime,Sources sources) {}
    public record Inventory(long skus,long observed,long partial,long unknown,long stale,
                            long unknownFulfillable,long observedZeroFulfillable,
                            long unknownSourceTime,Sources sources) {}
    public record Report(String id,String state,String after,String before,
                         String currency,int totalOrders,int processedOrders,
                         long eligibleOrders,long blockedOrders,String contribution,
                         boolean cohortFrozen,boolean globalSourceSnapshot,
                         boolean financialSourceComplete,boolean bankVerified,
                         boolean ledgerPosted) {}
    public record Marketplace(String id,String marketplaceId,String region,String currency,
                              Listings listings,Orders orders,Inventory inventory,
                              Report latestReport) {}
    public record Overview(String capturedAt,boolean sourceSnapshotFrozen,
                           boolean financialSourceComplete,boolean bankVerified,
                           boolean ledgerPosted,boolean crossCurrencyTotalsAvailable,
                           boolean mixedCurrencies,List<Marketplace> marketplaces) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Overview read(UUID tenant) {
        // This query is intentionally bounded. Never drop the 101st Marketplace
        // silently while labeling the rest a tenant-wide business overview.
        List<Marketplace> rows=jdbc.query("""
            SELECT mp.id,mp.marketplace_id,sa.region,mp.currency_code,
                li.total AS l_total,li.unmapped AS l_unmapped,
                li.conflict AS l_conflict,li.unknown_time AS l_unknown_time,
                li.mock AS l_mock,li.unverified AS l_unverified,li.live AS l_live,
                ord.total AS o_total,ord.items_missing AS o_items_missing,
                ord.finance_missing AS o_finance_missing,
                ord.refund_unknown AS o_refund_unknown,
                ord.unknown_time AS o_unknown_time,
                ord.mock AS o_mock,ord.unverified AS o_unverified,
                ord.live AS o_live,
                inv.skus AS i_skus,inv.observed AS i_observed,
                inv.partial AS i_partial,inv.unknown AS i_unknown,
                inv.stale AS i_stale,inv.fulfillable_unknown AS i_fulfillable_unknown,
                inv.observed_zero AS i_observed_zero,
                inv.unknown_time AS i_unknown_time,
                inv.mock AS i_mock,inv.unverified AS i_unverified,
                inv.live AS i_live,
                rep.id AS r_id,rep.state AS r_state,
                rep.period_after AS r_after,rep.period_before AS r_before,
                rep.currency_code AS r_currency,
                rep.total_orders AS r_total,rep.processed_orders AS r_processed,
                rep.eligible AS r_eligible,rep.blocked AS r_blocked,
                rep.contribution AS r_contribution
            FROM seller_marketplace mp
            JOIN seller_account sa ON sa.tenant_id=mp.tenant_id
                 AND sa.id=mp.seller_account_id
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE mapping_status='UNMAPPED') AS unmapped,
                  COUNT(*) FILTER (WHERE mapping_status='CONFLICT') AS conflict,
                  COUNT(*) FILTER (WHERE source_updated_at IS NULL) AS unknown_time,
                  COUNT(*) FILTER (WHERE data_origin='MOCK') AS mock,
                  COUNT(*) FILTER (WHERE data_origin='UNVERIFIED') AS unverified,
                  COUNT(*) FILTER (WHERE data_origin='LIVE') AS live
                FROM marketplace_listing l
                WHERE l.tenant_id=mp.tenant_id AND l.seller_marketplace_id=mp.id
            ) li ON TRUE
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE items_observation IS DISTINCT FROM 'OBSERVED')
                    AS items_missing,
                  COUNT(*) FILTER (WHERE financial_status='NOT_OBSERVED')
                    AS finance_missing,
                  COUNT(*) FILTER (WHERE refund_status='UNKNOWN') AS refund_unknown,
                  COUNT(*) FILTER (WHERE source_updated_at IS NULL) AS unknown_time,
                  COUNT(*) FILTER (WHERE data_origin='MOCK') AS mock,
                  COUNT(*) FILTER (WHERE data_origin='UNVERIFIED') AS unverified,
                  COUNT(*) FILTER (WHERE data_origin='LIVE') AS live
                FROM sales_order o
                WHERE o.tenant_id=mp.tenant_id AND o.seller_marketplace_id=mp.id
            ) ord ON TRUE
            LEFT JOIN LATERAL (
                WITH latest AS (
                  SELECT DISTINCT ON (seller_sku_raw)
                    quality,fulfillable,observed_at,data_origin
                  FROM fba_inventory_observation f
                  WHERE f.tenant_id=mp.tenant_id AND f.seller_marketplace_id=mp.id
                  ORDER BY seller_sku_raw,observed_at DESC NULLS LAST,
                           ingested_at DESC,id DESC
                )
                SELECT COUNT(*) AS skus,
                  COUNT(*) FILTER (WHERE quality='OBSERVED') AS observed,
                  COUNT(*) FILTER (WHERE quality='PARTIAL') AS partial,
                  COUNT(*) FILTER (WHERE quality='UNKNOWN') AS unknown,
                  COUNT(*) FILTER (WHERE quality='STALE') AS stale,
                  COUNT(*) FILTER (WHERE fulfillable IS NULL) AS fulfillable_unknown,
                  COUNT(*) FILTER (WHERE quality='OBSERVED' AND fulfillable=0)
                    AS observed_zero,
                  COUNT(*) FILTER (WHERE observed_at IS NULL) AS unknown_time,
                  COUNT(*) FILTER (WHERE data_origin='MOCK') AS mock,
                  COUNT(*) FILTER (WHERE data_origin='UNVERIFIED') AS unverified,
                  COUNT(*) FILTER (WHERE data_origin='LIVE') AS live
                FROM latest
            ) inv ON TRUE
            LEFT JOIN LATERAL (
                SELECT j.id,j.state,j.period_after,j.period_before,j.currency_code,
                       j.total_orders,j.processed_orders,
                       (SELECT COUNT(*) FROM finance_profit_report_order o
                        WHERE o.tenant_id=j.tenant_id AND o.job_id=j.id
                          AND o.state='ELIGIBLE') AS eligible,
                       (SELECT COUNT(*) FROM finance_profit_report_order o
                        WHERE o.tenant_id=j.tenant_id AND o.job_id=j.id
                          AND o.state='BLOCKED') AS blocked,
                       CASE WHEN j.state='SUCCEEDED' THEN
                          (SELECT SUM(o.estimated_contribution)
                           FROM finance_profit_report_order o
                           WHERE o.tenant_id=j.tenant_id AND o.job_id=j.id
                             AND o.state='ELIGIBLE')
                        ELSE NULL END AS contribution
                FROM finance_profit_report_job j
                WHERE j.tenant_id=mp.tenant_id AND j.seller_marketplace_id=mp.id
                ORDER BY j.created_at DESC,j.id DESC LIMIT 1
            ) rep ON TRUE
            WHERE mp.tenant_id=?
            ORDER BY mp.marketplace_id,mp.id LIMIT 101
            """,(r,n)->{
                var reportId=r.getObject("r_id",UUID.class);
                Report report=reportId==null?null:new Report(reportId.toString(),
                    r.getString("r_state"),
                    r.getObject("r_after",LocalDate.class).toString(),
                    r.getObject("r_before",LocalDate.class).toString(),
                    r.getString("r_currency").trim(),r.getInt("r_total"),
                    r.getInt("r_processed"),r.getLong("r_eligible"),
                    r.getLong("r_blocked"),
                    r.getBigDecimal("r_contribution")==null?null:
                        r.getBigDecimal("r_contribution").toPlainString(),
                    true,false,false,false,false);
                return new Marketplace(r.getObject("id",UUID.class).toString(),
                    r.getString("marketplace_id"),r.getString("region"),
                    r.getString("currency_code").trim(),
                    new Listings(r.getLong("l_total"),r.getLong("l_unmapped"),
                        r.getLong("l_conflict"),r.getLong("l_unknown_time"),
                        new Sources(r.getLong("l_mock"),r.getLong("l_unverified"),
                            r.getLong("l_live"))),
                    new Orders(r.getLong("o_total"),r.getLong("o_items_missing"),
                        r.getLong("o_finance_missing"),r.getLong("o_refund_unknown"),
                        r.getLong("o_unknown_time"),
                        new Sources(r.getLong("o_mock"),r.getLong("o_unverified"),
                            r.getLong("o_live"))),
                    new Inventory(r.getLong("i_skus"),r.getLong("i_observed"),
                        r.getLong("i_partial"),r.getLong("i_unknown"),
                        r.getLong("i_stale"),r.getLong("i_fulfillable_unknown"),
                        r.getLong("i_observed_zero"),r.getLong("i_unknown_time"),
                        new Sources(r.getLong("i_mock"),r.getLong("i_unverified"),
                            r.getLong("i_live"))),
                    report);
            },tenant);
        if(rows.size()>100)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Business overview exceeds 100 marketplaces; use scoped operational reads");
        boolean mixed=rows.stream().map(Marketplace::currency).distinct().count()>1;
        return new Overview(Instant.now().toString(),false,false,false,false,
            false,mixed,List.copyOf(rows));
    }

    /** A LIVE read, NOT the V64 immutable finance extract. Exact same rows,
     * fixed headers and anti-formula CSV encoding reused from V64/ProfitReportCsv.
     */
    static String csv(Overview snapshot) {
        StringBuilder out=new StringBuilder("\uFEFF");
        out.append(ProfitReportCsv.encode(List.of(
            "captured_at_utc","marketplace_uuid","marketplace_code","region",
            "original_currency","listings","listing_unmapped","listing_conflict",
            "listing_source_time_unknown","listing_mock","listing_unverified",
            "listing_live","orders","order_items_not_observed",
            "order_finance_not_observed","order_refund_unknown",
            "order_source_time_unknown","orders_mock","orders_unverified",
            "orders_live","latest_fba_skus","fba_observed","fba_partial",
            "fba_unknown","fba_stale","fba_fulfillable_unknown",
            "fba_observed_zero","fba_source_time_unknown","fba_mock",
            "fba_unverified","fba_live","v64_report_uuid","v64_report_state",
            "v64_period_after","v64_period_before","v64_currency",
            "v64_processed_orders","v64_total_orders","v64_eligible_orders",
            "v64_blocked_orders","v64_completed_contribution_only",
            "source_snapshot_frozen","financial_source_complete","bank_verified",
            "ledger_posted","cross_currency_totals_available")));
        for(var m:snapshot.marketplaces()) {
            Listings l=m.listings();Orders o=m.orders();Inventory i=m.inventory();
            Report f=m.latestReport();
            out.append(ProfitReportCsv.encode(List.of(
                snapshot.capturedAt(),m.id(),m.marketplaceId(),m.region(),
                m.currency(),n(l.total()),n(l.unmapped()),n(l.conflicts()),
                n(l.unknownSourceTime()),n(l.sources().mock()),
                n(l.sources().unverified()),n(l.sources().live()),
                n(o.total()),n(o.itemsNotObserved()),n(o.financeNotObserved()),
                n(o.unknownRefund()),n(o.unknownSourceTime()),n(o.sources().mock()),
                n(o.sources().unverified()),n(o.sources().live()),
                n(i.skus()),n(i.observed()),n(i.partial()),n(i.unknown()),
                n(i.stale()),n(i.unknownFulfillable()),n(i.observedZeroFulfillable()),
                n(i.unknownSourceTime()),n(i.sources().mock()),
                n(i.sources().unverified()),n(i.sources().live()),
                f==null?"":f.id(),f==null?"":f.state(),
                f==null?"":f.after(),f==null?"":f.before(),
                f==null?"":f.currency(),f==null?"":n(f.processedOrders()),
                f==null?"":n(f.totalOrders()),f==null?"":n(f.eligibleOrders()),
                f==null?"":n(f.blockedOrders()),
                f==null||f.contribution()==null?"":f.contribution(),
                "false","false","false","false","false")));
        }
        return out.toString();
    }
    private static String n(long value){return Long.toString(value);}
}
