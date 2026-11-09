package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V72 read-only PO delivery dashboard over V20 actual receipts, V43 local
 * supplier-reported ASNs, V45 discrepancies, V46 QC release and V37 replenishment
 * origin. Never presumes an unreported shipment, freight ETA or FBA stock.
 */
@Service
public class PurchaseDeliveryService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Autowired
    public PurchaseDeliveryService(JdbcTemplate jdbc) {
        this(jdbc,Clock.systemUTC());
    }
    PurchaseDeliveryService(JdbcTemplate jdbc,Clock clock) {
        this.jdbc=jdbc;this.clock=clock;
    }

    public record LineProgress(String orderId,String orderNumber,String orderStatus,
        String supplierId,String supplierName,String currency,
        String lineId,String productSkuId,String internalSku,
        int orderedQuantity,long receivedQuantity,long remainingQuantity,
        long draftAsnQuantity,long shippedAsnQuantity,long reportedUnreceivedQuantity,
        long unlinkedReceiptQuantity,long awaitingQcReleaseQuantity,
        long releasedQcAcceptedQuantity,long qcUnknownQuantity,
        long unresolvedVarianceCount,String earliestReportedEta,String latestReportedEta,
        String replenishmentSellerSku,String replenishmentPricingSource,
        String deliveryState) {}

    public record Totals(int lineCount,long pendingQuantity,long reportedUnreceivedQuantity,
                         long awaitingQcReleaseQuantity,long warningLines,
                         long overdueLines,long unresolvedVarianceLines) {}
    public record Overview(String asOfDateUtc,Totals totals,List<LineProgress> lines) {}
    public record FullSummary(String asOfDateUtc,Totals totals) {}

    /** "Shipped" only means locally entered supplier claim, not carrier proof. */
    static String classify(long remaining,long draft,long shippedOutstanding,
                           long qcAwaiting,long qcUnknown,long variances,
                           LocalDate earliest,LocalDate today) {
        if(variances>0)return "DISCREPANCY_REVIEW";
        if(remaining==0) {
            if(qcAwaiting>0)return "AWAITING_QC_RELEASE";
            if(qcUnknown>0)return "QC_EVIDENCE_UNKNOWN";
            return "LOCAL_RECEIVED";
        }
        if(shippedOutstanding>0 && earliest!=null && earliest.isBefore(today))
            return "REPORTED_ETA_OVERDUE";
        if(shippedOutstanding>0 && earliest!=null && !earliest.isAfter(today.plusDays(3)))
            return "REPORTED_ETA_DUE_SOON";
        if(shippedOutstanding>0)return "SHIPMENT_REPORTED";
        if(draft>0)return "ASN_AWAITING_DISPATCH";
        return "NO_ASN_RECORDED";
    }

    private static String date(ResultSet rs,String name) throws SQLException {
        LocalDate d=rs.getObject(name,LocalDate.class);
        return d==null?null:d.toString();
    }
    private static String state(LineProgress p) {
        return p.deliveryState();
    }
    /** One source-of-truth fact query for legacy view, full keyset and global SQL totals.
     * Rows are local observations, not carrier-confirmed deliveries or Amazon FBA. */
    private static final String DELIVERY_SQL="""
            SELECT o.updated_at AS cursor_updated_at,o.id AS order_id,o.order_number,o.status AS order_status,
              o.supplier_id,sup.display_name AS supplier_name,o.currency_code,
              l.id AS line_id,l.product_sku_id,sku.internal_sku,l.ordered_quantity,
              receipt.received_units,receipt.unlinked_units,
              receipt.qc_awaiting_units,receipt.qc_released_units,
              receipt.qc_unknown_units,
              asns.draft_units,asns.shipped_units,asns.shipped_outstanding,
              asns.earliest_eta,asns.latest_eta,
              variance.unresolved_count,source.seller_sku AS replenishment_seller_sku,
              source.supplier_pricing_source AS replenishment_pricing_source
            FROM erp_purchase_order o
            JOIN erp_supplier sup ON sup.tenant_id=o.tenant_id AND sup.id=o.supplier_id
            JOIN erp_purchase_order_line l ON l.tenant_id=o.tenant_id
              AND l.purchase_order_id=o.id
            JOIN product_sku sku ON sku.tenant_id=l.tenant_id AND sku.id=l.product_sku_id
            LEFT JOIN erp_replenishment_purchase_draft_source source
              ON source.tenant_id=l.tenant_id AND source.purchase_order_id=o.id
              AND source.product_sku_id=l.product_sku_id
            LEFT JOIN LATERAL (
              SELECT COALESCE(SUM(r.quantity),0)::bigint AS received_units,
                COALESCE(SUM(r.quantity) FILTER (WHERE r.asn_id IS NULL),0)::bigint
                  AS unlinked_units,
                COALESCE(SUM(r.quantity) FILTER
                  (WHERE b.status IN ('PENDING_QC','INSPECTED')),0)::bigint
                  AS qc_awaiting_units,
                COALESCE(SUM(q.accepted_quantity) FILTER
                  (WHERE b.status='RELEASED'),0)::bigint AS qc_released_units,
                COALESCE(SUM(r.quantity) FILTER
                  (WHERE b.receipt_id IS NULL),0)::bigint AS qc_unknown_units
              FROM erp_purchase_receipt r
              LEFT JOIN erp_receipt_quality_batch b ON b.tenant_id=r.tenant_id
                AND b.receipt_id=r.id
              LEFT JOIN erp_purchase_receipt_qc q ON q.tenant_id=b.tenant_id
                AND q.id=b.inspection_id
              WHERE r.tenant_id=l.tenant_id AND r.purchase_order_id=o.id
                AND r.purchase_order_line_id=l.id
            ) receipt ON TRUE
            LEFT JOIN LATERAL (
              SELECT COALESCE(SUM(a.quantity) FILTER (WHERE a.status='DRAFT'),0)::bigint
                  AS draft_units,
                COALESCE(SUM(a.quantity) FILTER (WHERE a.status='SHIPPED'),0)::bigint
                  AS shipped_units,
                COALESCE(SUM(GREATEST(a.quantity-a.linked_received,0))
                  FILTER (WHERE a.status='SHIPPED'),0)::bigint AS shipped_outstanding,
                MIN(a.expected_arrival_date) FILTER
                  (WHERE a.status='SHIPPED' AND a.quantity>a.linked_received) AS earliest_eta,
                MAX(a.expected_arrival_date) FILTER
                  (WHERE a.status='SHIPPED' AND a.quantity>a.linked_received) AS latest_eta
              FROM (
                SELECT a.id,a.status,a.quantity,a.expected_arrival_date,
                  (SELECT COALESCE(SUM(linked.quantity),0)
                   FROM erp_purchase_receipt linked
                   WHERE linked.tenant_id=a.tenant_id AND linked.asn_id=a.id)
                   AS linked_received
                FROM erp_purchase_asn a
                WHERE a.tenant_id=l.tenant_id AND a.purchase_order_id=o.id
                  AND a.purchase_order_line_id=l.id
                  AND a.status<>'CANCELLED'
              ) a
            ) asns ON TRUE
            LEFT JOIN LATERAL (
              SELECT COUNT(*)::bigint AS unresolved_count
              FROM erp_purchase_variance v WHERE v.tenant_id=l.tenant_id
                AND v.purchase_order_id=o.id AND v.purchase_order_line_id=l.id
                AND v.state IN ('OPEN','REVIEWED')
            ) variance ON TRUE
            WHERE o.tenant_id=? AND o.status IN ('APPROVED','RECEIVING','RECEIVED')
            """;

    private record ScannedLine(LineProgress line,OffsetDateTime updatedAt) {}

    private static LineProgress mapLine(ResultSet rs,int index,LocalDate today)
        throws SQLException {
                long ordered=rs.getLong("ordered_quantity");
                long received=rs.getLong("received_units");
                long remaining=Math.max(0,ordered-received);
                long draft=rs.getLong("draft_units"),reported=rs.getLong("shipped_outstanding");
                long qcAwaiting=rs.getLong("qc_awaiting_units");
                long qcUnknown=rs.getLong("qc_unknown_units");
                long variances=rs.getLong("unresolved_count");
                LocalDate eta=rs.getObject("earliest_eta",LocalDate.class);
                String status=classify(remaining,draft,reported,qcAwaiting,qcUnknown,variances,eta,today);
                return new LineProgress(rs.getObject("order_id",UUID.class).toString(),
                    rs.getString("order_number"),rs.getString("order_status"),
                    rs.getObject("supplier_id",UUID.class).toString(),rs.getString("supplier_name"),
                    rs.getString("currency_code").trim(),
                    rs.getObject("line_id",UUID.class).toString(),
                    rs.getObject("product_sku_id",UUID.class).toString(),
                    rs.getString("internal_sku"),rs.getInt("ordered_quantity"),received,
                    remaining,draft,rs.getLong("shipped_units"),reported,
                    rs.getLong("unlinked_units"),qcAwaiting,
                    rs.getLong("qc_released_units"),qcUnknown,variances,
                    date(rs,"earliest_eta"),date(rs,"latest_eta"),
                    rs.getString("replenishment_seller_sku"),
                    rs.getString("replenishment_pricing_source"),status);
    }

    private void requireActiveTenant(UUID tenant) {
        Integer active=jdbc.queryForObject("""
            SELECT COUNT(*) FROM tenant WHERE id=? AND status='ACTIVE'
            """,Integer.class,tenant);
        if(active==null || active!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant unavailable");
    }

    /** V119: complete approved lines under a tenant-bound keyset cursor. */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<LineProgress> page(UUID tenant,int requested,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String[] c=LocalKeysetPage.decode(cursor,5);
        OffsetDateTime afterTime=null;UUID afterOrder=null,afterLine=null;
        if(c!=null){
            if(!"delivery-v119".equals(c[0]) || !tenant.toString().equals(c[1]))
                throw LocalKeysetPage.bad("Delivery page cursor scope mismatch");
            try {
                afterTime=OffsetDateTime.parse(c[2]);
                afterOrder=UUID.fromString(c[3]);
                afterLine=UUID.fromString(c[4]);
            } catch(IllegalArgumentException ex) {
                throw LocalKeysetPage.bad("Invalid delivery page cursor");
            }
        }
        requireActiveTenant(tenant);
        LocalDate today=LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String q=DELIVERY_SQL
            +(c==null?"":" AND (o.updated_at,o.id,l.id)<(?,?,?)")
            +" ORDER BY o.updated_at DESC,o.id DESC,l.id DESC LIMIT ?";
        List<ScannedLine> fetched=c==null
            ?jdbc.query(q,(rs,i)->new ScannedLine(mapLine(rs,i,today),
                rs.getObject("cursor_updated_at",OffsetDateTime.class)),tenant,limit+1)
            :jdbc.query(q,(rs,i)->new ScannedLine(mapLine(rs,i,today),
                rs.getObject("cursor_updated_at",OffsetDateTime.class)),
                tenant,afterTime,afterOrder,afterLine,limit+1);
        var p=LocalKeysetPage.pack(fetched,limit,row->LocalKeysetPage.encode(
            "delivery-v119",tenant.toString(),row.updatedAt().toString(),
            row.line().orderId(),row.line().lineId()));
        return new LocalKeysetPage.Page<>(p.items().stream()
            .map(ScannedLine::line).toList(),p.nextCursor(),p.hasMore(),p.pageSize());
    }

    /** V119: PostgreSQL aggregates over all approved tenant lines, not LIMIT 300. */
    @Transactional(readOnly=true)
    public FullSummary globalSummary(UUID tenant) {
        requireActiveTenant(tenant);
        LocalDate today=LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String q="""
            WITH delivery AS (
            """+DELIVERY_SQL+"""
            ), classified AS (
              SELECT d.*, CASE
                WHEN unresolved_count>0 THEN 'DISCREPANCY_REVIEW'
                WHEN GREATEST(ordered_quantity-received_units,0)=0
                     AND qc_awaiting_units>0 THEN 'AWAITING_QC_RELEASE'
                WHEN GREATEST(ordered_quantity-received_units,0)=0
                     AND qc_unknown_units>0 THEN 'QC_EVIDENCE_UNKNOWN'
                WHEN GREATEST(ordered_quantity-received_units,0)=0 THEN 'LOCAL_RECEIVED'
                WHEN shipped_outstanding>0 AND earliest_eta IS NOT NULL
                     AND earliest_eta<? THEN 'REPORTED_ETA_OVERDUE'
                WHEN shipped_outstanding>0 AND earliest_eta IS NOT NULL
                     AND earliest_eta<=(CAST(? AS date)+3) THEN 'REPORTED_ETA_DUE_SOON'
                WHEN shipped_outstanding>0 THEN 'SHIPMENT_REPORTED'
                WHEN draft_units>0 THEN 'ASN_AWAITING_DISPATCH'
                ELSE 'NO_ASN_RECORDED'
              END AS delivery_state FROM delivery d
            )
            SELECT COUNT(*) AS line_count,
              COALESCE(SUM(GREATEST(ordered_quantity-received_units,0)),0)
                AS pending_quantity,
              COALESCE(SUM(shipped_outstanding),0) AS reported_unreceived_quantity,
              COALESCE(SUM(qc_awaiting_units),0) AS awaiting_qc_release_quantity,
              COALESCE(SUM(CASE WHEN delivery_state NOT IN
                ('LOCAL_RECEIVED','SHIPMENT_REPORTED') THEN 1 ELSE 0 END),0)
                AS warning_lines,
              COALESCE(SUM(CASE WHEN delivery_state='REPORTED_ETA_OVERDUE'
                THEN 1 ELSE 0 END),0) AS overdue_lines,
              COALESCE(SUM(CASE WHEN unresolved_count>0
                THEN 1 ELSE 0 END),0) AS unresolved_variance_lines
            FROM classified
            """;
        Totals totals=jdbc.queryForObject(q,(rs,i)->new Totals(
            Math.toIntExact(rs.getLong("line_count")),
            rs.getLong("pending_quantity"),
            rs.getLong("reported_unreceived_quantity"),
            rs.getLong("awaiting_qc_release_quantity"),
            rs.getLong("warning_lines"),rs.getLong("overdue_lines"),
            rs.getLong("unresolved_variance_lines")),tenant,today,today);
        return new FullSummary(today.toString(),totals);
    }

    @Transactional(readOnly=true)
    public Overview overview(UUID tenant) {
        requireActiveTenant(tenant);
        LocalDate today=LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<LineProgress> lines=jdbc.query(DELIVERY_SQL
            +" ORDER BY o.updated_at DESC,o.id DESC,l.id DESC LIMIT 300",
            (rs,i)->mapLine(rs,i,today),tenant);
        long pending=0,reported=0,awaiting=0,warnings=0,overdue=0,varianceLines=0;
        for(LineProgress p:lines) {
            pending+=p.remainingQuantity();
            reported+=p.reportedUnreceivedQuantity();
            awaiting+=p.awaitingQcReleaseQuantity();
            if(!List.of("LOCAL_RECEIVED","SHIPMENT_REPORTED").contains(state(p)))warnings++;
            if("REPORTED_ETA_OVERDUE".equals(state(p)))overdue++;
            if(p.unresolvedVarianceCount()>0)varianceLines++;
        }
        return new Overview(today.toString(),new Totals(lines.size(),pending,
            reported,awaiting,warnings,overdue,varianceLines),lines);
    }
}
