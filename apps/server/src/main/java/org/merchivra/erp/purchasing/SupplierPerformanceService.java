package org.merchivra.erp.purchasing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 90-day *local evidence* scorecards, not verified carrier OTIF. Only fully
 * ASN-linked physical receipts count toward on-time, compared with the latest
 * supplier-entered ETA event that existed at actual receipt time.
 */
@Service
public class SupplierPerformanceService {
    private final JdbcTemplate jdbc;
    public SupplierPerformanceService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Scorecard(String supplierId,String supplierName,
        int completeAsnSampleCount,int observedOnTimeAsnCount,
        int inspectedReceiptSampleCount,long acceptedQcUnits,long inspectedQcUnits,
        long activeVarianceCount,String observedOnTimePercent,
        String inspectedAcceptancePercent,String evidenceScore,String scoringStatus) {}

    /** Require >=3 complete ASN and >=3 QC samples before issuing a provisional score. */
    static String score(int completed,int onTime,int qcSamples,long accepted,long inspected) {
        if(completed<3 || qcSamples<3 || inspected<1 || onTime<0
           || onTime>completed || accepted<0 || accepted>inspected)return null;
        BigDecimal punctual=BigDecimal.valueOf(onTime).divide(
            BigDecimal.valueOf(completed),8,RoundingMode.HALF_UP);
        BigDecimal quality=BigDecimal.valueOf(accepted).divide(
            BigDecimal.valueOf(inspected),8,RoundingMode.HALF_UP);
        return punctual.multiply(new BigDecimal("60"))
            .add(quality.multiply(new BigDecimal("40")))
            .setScale(1,RoundingMode.HALF_UP).toPlainString();
    }
    static String ratio(long n,long d) {
        if(d<=0 || n<0 || n>d)return null;
        return BigDecimal.valueOf(n).multiply(new BigDecimal("100"))
            .divide(BigDecimal.valueOf(d),1,RoundingMode.HALF_UP).toPlainString();
    }
    private static Scorecard map(ResultSet rs,int i) throws SQLException {
        int total=rs.getInt("complete_asns"),onTime=rs.getInt("on_time_asns"),
            samples=rs.getInt("qc_samples");
        long accepted=rs.getLong("accepted_units"),inspected=rs.getLong("inspected_units");
        String score=score(total,onTime,samples,accepted,inspected);
        return new Scorecard(rs.getObject("supplier_id",UUID.class).toString(),
            rs.getString("display_name"),total,onTime,samples,accepted,inspected,
            rs.getLong("unresolved_count"),ratio(onTime,total),ratio(accepted,inspected),
            score,score==null?"INSUFFICIENT_EVIDENCE":"PROVISIONAL");
    }

    @Transactional(readOnly=true)
    public List<Scorecard> scorecards(UUID tenant) {
        return jdbc.query("""
            SELECT s.id AS supplier_id,s.display_name,
              COALESCE(a.complete_asns,0)::integer AS complete_asns,
              COALESCE(a.on_time_asns,0)::integer AS on_time_asns,
              COALESCE(q.qc_samples,0)::integer AS qc_samples,
              COALESCE(q.accepted_units,0)::bigint AS accepted_units,
              COALESCE(q.inspected_units,0)::bigint AS inspected_units,
              COALESCE(v.unresolved_count,0)::bigint AS unresolved_count
            FROM erp_supplier s
            LEFT JOIN (
              SELECT o.supplier_id,
                COUNT(*)::integer AS complete_asns,
                COUNT(*) FILTER (WHERE
                  (linked.latest_received AT TIME ZONE 'UTC')::date <= historical_eta.new_eta)
                  ::integer AS on_time_asns
              FROM erp_purchase_asn a
              JOIN erp_purchase_order o ON o.tenant_id=a.tenant_id
                AND o.id=a.purchase_order_id
              JOIN LATERAL (
                SELECT COALESCE(SUM(r.quantity),0)::bigint AS units,
                   MAX(r.received_at) AS latest_received
                FROM erp_purchase_receipt r
                WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id
              ) linked ON TRUE
              JOIN LATERAL (
                SELECT ev.new_eta
                FROM erp_purchase_asn_event ev
                WHERE ev.tenant_id=a.tenant_id AND ev.asn_id=a.id
                  AND ev.event_type IN ('CREATED','ETA_UPDATED')
                  AND ev.created_at<=linked.latest_received
                  AND ev.new_eta IS NOT NULL
                ORDER BY ev.created_at DESC,ev.id DESC LIMIT 1
              ) historical_eta ON TRUE
              WHERE a.tenant_id=? AND a.status='SHIPPED'
                AND linked.units=a.quantity
                AND linked.latest_received>=now()-interval '90 days'
              GROUP BY o.supplier_id
            ) a ON a.supplier_id=s.id
            LEFT JOIN (
              SELECT o.supplier_id,COUNT(*)::integer AS qc_samples,
                COALESCE(SUM(q.accepted_quantity),0)::bigint AS accepted_units,
                COALESCE(SUM(q.inspected_quantity),0)::bigint AS inspected_units
              FROM erp_purchase_receipt_qc q
              JOIN erp_purchase_receipt r ON r.tenant_id=q.tenant_id
                AND r.id=q.purchase_receipt_id
              JOIN erp_purchase_order o ON o.tenant_id=r.tenant_id
                AND o.id=r.purchase_order_id
              WHERE q.tenant_id=? AND q.created_at>=now()-interval '90 days'
              GROUP BY o.supplier_id
            ) q ON q.supplier_id=s.id
            LEFT JOIN (
              SELECT o.supplier_id,COUNT(*)::bigint AS unresolved_count
              FROM erp_purchase_variance v
              JOIN erp_purchase_order o ON o.tenant_id=v.tenant_id
                AND o.id=v.purchase_order_id
              WHERE v.tenant_id=? AND v.state IN ('OPEN','REVIEWED')
              GROUP BY o.supplier_id
            ) v ON v.supplier_id=s.id
            WHERE s.tenant_id=? AND s.status='ACTIVE'
            ORDER BY s.display_name,s.id LIMIT 200
            """,SupplierPerformanceService::map,tenant,tenant,tenant,tenant);
    }
}
