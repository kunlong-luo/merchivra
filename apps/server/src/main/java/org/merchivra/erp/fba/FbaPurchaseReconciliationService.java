package org.merchivra.erp.fba;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V76 read-only local intent reconciliation. It cross-displays source evidence,
 * NEVER equates a PO receipt with Amazon FBA arrival or subtracts either side.
 */
@Service
public class FbaPurchaseReconciliationService {
    private final JdbcTemplate jdbc;
    public FbaPurchaseReconciliationService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Line(String shipmentId,String shipmentReference,String shipmentState,
        String shipmentOrigin,String inboundLineId,String sellerSku,
        int plannedQuantity,long locallyReportedQuantity,
        String localVarianceDecision,
        String linkId,String linkState,String proofReference,
        String purchaseOrderId,String purchaseOrderNumber,String purchaseOrderState,
        Integer purchaseOrderedQuantity,Long purchaseLocalReceivedQuantity,
        String asnId,String asnState,Integer asnReportedQuantity,
        Long asnLocalReceivedQuantity,String findingCode,String severity,
        String recommendedAction) {}
    public record Totals(int examinedLines,int high,int warning,int informational,
                         int pendingIdentity,int unlinked,int localVariance) {}
    public record Overview(boolean truncated,int shipmentScope,Totals totals,
                           List<Line> lines,boolean amazonVerified,
                           boolean fbaStockCredited) {}
    public record Finding(String code,String severity,String action) {}
    private static final String SQL = """
        WITH recent_shipment AS (
          SELECT id FROM fba_inbound_shipment
          WHERE tenant_id=? AND seller_marketplace_id=?
          ORDER BY created_at DESC,id DESC LIMIT 100
        )
        SELECT s.id AS shipment_id,s.reference,s.state AS shipment_state,
          s.evidence_origin,l.id AS inbound_line_id,l.seller_sku,l.planned_quantity,
          COALESCE(reported.quantity,0)::bigint AS locally_reported,
          resolution.decision AS variance_decision,
          proof.id AS link_id,proof.state AS link_state,proof.proof_reference,
          po.id AS po_id,po.order_number,po.status AS po_state,
          po_line.ordered_quantity AS po_quantity,
          COALESCE(local_receipt.quantity,0)::bigint AS po_received,
          a.id AS asn_id,a.status AS asn_state,a.quantity AS asn_quantity,
          COALESCE(asn_receipt.quantity,0)::bigint AS asn_received
        FROM recent_shipment recent
        JOIN fba_inbound_shipment s ON s.id=recent.id AND s.tenant_id=?
          AND s.seller_marketplace_id=?
        JOIN fba_inbound_line l ON l.tenant_id=s.tenant_id AND l.shipment_id=s.id
        LEFT JOIN LATERAL (
          SELECT SUM(e.quantity)::bigint AS quantity
          FROM fba_inbound_receipt_event e
          WHERE e.tenant_id=l.tenant_id AND e.shipment_id=l.shipment_id
            AND e.line_id=l.id
        ) reported ON TRUE
        LEFT JOIN fba_inbound_variance_resolution resolution
          ON resolution.tenant_id=l.tenant_id AND resolution.shipment_id=l.shipment_id
          AND resolution.line_id=l.id
        LEFT JOIN fba_inbound_purchase_link proof
          ON proof.tenant_id=l.tenant_id AND proof.shipment_id=l.shipment_id
          AND proof.inbound_line_id=l.id AND proof.state<>'REVOKED'
        LEFT JOIN erp_purchase_order_line po_line
          ON po_line.tenant_id=proof.tenant_id
          AND po_line.id=proof.purchase_order_line_id
        LEFT JOIN erp_purchase_order po ON po.tenant_id=proof.tenant_id
          AND po.id=proof.purchase_order_id
        LEFT JOIN erp_purchase_asn a ON a.tenant_id=proof.tenant_id
          AND a.id=proof.asn_id AND a.purchase_order_line_id=po_line.id
        LEFT JOIN LATERAL (
          SELECT SUM(r.quantity)::bigint AS quantity
          FROM erp_purchase_receipt r
          WHERE r.tenant_id=po_line.tenant_id
            AND r.purchase_order_line_id=po_line.id
        ) local_receipt ON TRUE
        LEFT JOIN LATERAL (
          SELECT SUM(r.quantity)::bigint AS quantity
          FROM erp_purchase_receipt r
          WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id
        ) asn_receipt ON TRUE
        ORDER BY s.created_at DESC,s.id DESC,l.seller_sku,l.id
        LIMIT 501
        """;

    /** Evaluate only facts with shared semantic meaning; never subtract PO
     * receipts from local FBA intent or call a quantity mismatch a lost parcel. */
    static Finding classify(String shipmentState,int planned,long reported,
                            String varianceDecision,String linkState,
                            String poState,String asnState,boolean hasAsn) {
        if(reported>planned && !"ACCEPT_VARIANCE".equals(varianceDecision))
            return new Finding("LOCAL_OVER_REPORT","HIGH","REVIEW_LOCAL_VARIANCE");
        if("PROPOSED".equals(linkState))
            return new Finding("PENDING_IDENTITY_REVIEW","WARNING","SECOND_OWNER_REVIEW");
        if("CONFIRMED".equals(linkState) &&
           (!("APPROVED".equals(poState)||"RECEIVING".equals(poState)
             ||"RECEIVED".equals(poState))
           || hasAsn && !"SHIPPED".equals(asnState)))
            return new Finding("LINK_SOURCE_CHANGED","WARNING","RECHECK_SOURCE_IDENTITY");
        if("REVIEWING".equals(shipmentState) && planned!=reported &&
           !"ACCEPT_VARIANCE".equals(varianceDecision))
            return new Finding("LOCAL_VARIANCE_PENDING","WARNING","REVIEW_LOCAL_VARIANCE");
        if(linkState==null && reported>0)
            return new Finding("LOCAL_REPORT_WITHOUT_IDENTITY","WARNING","REVIEW_MISSING_LINK");
        if(linkState==null && !"DRAFT".equals(shipmentState))
            return new Finding("DISPATCHED_WITHOUT_LINK","WARNING","REVIEW_MISSING_LINK");
        if(linkState==null)
            return new Finding("UNLINKED_DRAFT","INFO","OPTIONAL_MANUAL_LINK");
        if("ACCEPT_VARIANCE".equals(varianceDecision))
            return new Finding("LOCAL_VARIANCE_ACCEPTED","INFO","NONE");
        if(!hasAsn)
            return new Finding("CONFIRMED_PO_ONLY","INFO","OPTIONAL_ASN_EVIDENCE");
        return new Finding("IDENTITY_CONFIRMED","INFO","NONE");
    }
    private static String uuid(ResultSet r,String column)throws SQLException{
        UUID id=r.getObject(column,UUID.class);
        return id==null?null:id.toString();
    }
    private static Line map(ResultSet r,int index)throws SQLException{
        String asn=uuid(r,"asn_id");
        String link=uuid(r,"link_id");
        int planned=r.getInt("planned_quantity");
        long reported=r.getLong("locally_reported");
        String state=r.getString("shipment_state"),linkState=r.getString("link_state");
        String variance=r.getString("variance_decision");
        String poState=r.getString("po_state"),asnState=r.getString("asn_state");
        Finding finding=classify(state,planned,reported,variance,linkState,poState,
                                  asnState,asn!=null);
        return new Line(uuid(r,"shipment_id"),r.getString("reference"),state,
            r.getString("evidence_origin"),uuid(r,"inbound_line_id"),
            r.getString("seller_sku"),planned,reported,variance,link,linkState,
            r.getString("proof_reference"),uuid(r,"po_id"),
            r.getString("order_number"),poState,
            (Integer)r.getObject("po_quantity"),(Long)r.getObject("po_received"),
            asn,asnState,(Integer)r.getObject("asn_quantity"),
            (Long)r.getObject("asn_received"),finding.code(),finding.severity(),
            finding.action());
    }
    @Transactional(readOnly=true)
    public Overview overview(UUID tenant,UUID marketplace) {
        Integer active=jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,Integer.class,tenant,marketplace);
        if(active==null||active!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Marketplace unavailable");
        List<Line> fetched=jdbc.query(SQL,FbaPurchaseReconciliationService::map,
                                     tenant,marketplace,tenant,marketplace);
        boolean truncated=fetched.size()>500;
        List<Line> lines=List.copyOf(truncated?fetched.subList(0,500):fetched);
        int high=0,warning=0,informational=0,pending=0,unlinked=0,variance=0;
        for(Line line:lines) {
            switch(line.severity()){
                case "HIGH" -> high++;
                case "WARNING" -> warning++;
                default -> informational++;
            }
            if("PENDING_IDENTITY_REVIEW".equals(line.findingCode()))pending++;
            if(line.linkId()==null)unlinked++;
            if(List.of("LOCAL_OVER_REPORT","LOCAL_VARIANCE_PENDING")
               .contains(line.findingCode()))variance++;
        }
        // shipmentScope is a bounded 100-most-recent window, never all-time.
        int scope=jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
              SELECT id FROM fba_inbound_shipment
              WHERE tenant_id=? AND seller_marketplace_id=?
              ORDER BY created_at DESC,id DESC LIMIT 100
            ) bounded
            """,Integer.class,tenant,marketplace);
        return new Overview(truncated,scope,
            new Totals(lines.size(),high,warning,informational,pending,unlinked,variance),
            lines,false,false);
    }
}
