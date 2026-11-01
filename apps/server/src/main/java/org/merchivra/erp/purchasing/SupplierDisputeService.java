package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V45 observational purchase-line discrepancies and local supplier claim workflow.
 * None of these records posts stock, verifies a carrier, issues a supplier order,
 * creates a refund, or changes Amazon/FBA inventory.
 */
@Service
public class SupplierDisputeService {
    private final JdbcTemplate jdbc;
    public SupplierDisputeService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Report(UUID requestKey,UUID lineId,String kind,UUID asnId,
                         int reportedExcessQuantity,String reason) {}
    public record Variance(String id,String orderId,String lineId,String asnId,
                           String kind,int expectedQuantity,int observedQuantity,
                           int varianceQuantity,String reason,String state,String createdBy,
                           String reviewedBy,String createdAt) {}
    public record ClaimRequest(UUID requestKey,UUID varianceId,UUID inspectionId,
                               int requestedQuantity,String remedy,String reason) {}
    public record Claim(String id,String orderId,String supplierId,String varianceId,
                        String inspectionId,int requestedQuantity,String remedy,
                        String reason,String status,String createdBy,String approvedBy,
                        String resolutionNote,String createdAt) {}
    public record Performance(String supplierId,String supplierName,long orderCount,
                              long orderedUnits,long receivedUnits,long rejectedUnits,
                              long openDiscrepancies,long openClaims,long overdueAsns) {}
    public record Event(String id,String eventType,String actorUserId,
                        String note,String createdAt) {}
    private record OrderState(String status,String supplierId) {}
    private record Source(String orderId,String supplierId,long maxQuantity,
                          String status) {}
    private record Quantities(int ordered,long received) {}
    private record AsnQuantities(int quantity,long received,String status) {}

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);
    }
    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT,msg);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Purchase dispute source not found");
    }
    private static String instant(ResultSet rs,String column) throws SQLException {
        OffsetDateTime date=rs.getObject(column,OffsetDateTime.class);
        return date==null?null:date.toInstant().toString();
    }
    private static String reason(String raw) {
        if(raw==null || raw.strip().length()<5 || raw.strip().length()>300
            || raw.chars().anyMatch(Character::isISOControl))
            throw bad("Reason must contain 5..300 printable characters");
        return raw.strip();
    }
    private void tenantLock(UUID tenant) {
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private OrderState orderLock(UUID tenant,UUID order) {
        List<OrderState> rows=jdbc.query("""
            SELECT status,supplier_id FROM erp_purchase_order
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new OrderState(rs.getString("status"),
                rs.getObject("supplier_id",UUID.class).toString()),tenant,order);
        return rows.stream().findFirst().orElseThrow(SupplierDisputeService::missing);
    }
    private Quantities quantities(UUID tenant,UUID order,UUID line) {
        return jdbc.query("""
            SELECT l.ordered_quantity,
              COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=l.tenant_id AND r.purchase_order_id=l.purchase_order_id
                  AND r.purchase_order_line_id=l.id),0)::bigint AS received_units
            FROM erp_purchase_order_line l
            WHERE l.tenant_id=? AND l.purchase_order_id=? AND l.id=?
            """,(rs,i)->new Quantities(rs.getInt("ordered_quantity"),
                rs.getLong("received_units")),tenant,order,line)
            .stream().findFirst().orElseThrow(SupplierDisputeService::missing);
    }
    private AsnQuantities asnQuantities(UUID tenant,UUID order,UUID line,UUID asn) {
        return jdbc.query("""
            SELECT a.quantity,a.status,
             COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id),0)::bigint AS received_units
            FROM erp_purchase_asn a
            WHERE a.tenant_id=? AND a.purchase_order_id=?
              AND a.purchase_order_line_id=? AND a.id=?
            """,(rs,i)->new AsnQuantities(rs.getInt("quantity"),
                rs.getLong("received_units"),rs.getString("status")),
                tenant,order,line,asn)
            .stream().findFirst().orElseThrow(SupplierDisputeService::missing);
    }
    private static Variance mapVariance(ResultSet rs,int i) throws SQLException {
        UUID asn=rs.getObject("asn_id",UUID.class);
        UUID reviewed=rs.getObject("reviewed_by",UUID.class);
        return new Variance(rs.getObject("id",UUID.class).toString(),
            rs.getObject("purchase_order_id",UUID.class).toString(),
            rs.getObject("purchase_order_line_id",UUID.class).toString(),
            asn==null?null:asn.toString(),rs.getString("kind"),
            rs.getInt("expected_quantity"),rs.getInt("observed_quantity"),
            rs.getInt("variance_quantity"),rs.getString("reason"),
            rs.getString("state"),rs.getObject("created_by",UUID.class).toString(),
            reviewed==null?null:reviewed.toString(),instant(rs,"created_at"));
    }
    private static final String VARIANCE_SQL = """
        SELECT id,purchase_order_id,purchase_order_line_id,asn_id,
          kind,expected_quantity,observed_quantity,variance_quantity,
          reason,state,created_by,reviewed_by,created_at
        FROM erp_purchase_variance WHERE tenant_id=?
        """;
    private Variance variance(UUID tenant,UUID id) {
        return jdbc.query(VARIANCE_SQL+" AND id=?",SupplierDisputeService::mapVariance,
            tenant,id).stream().findFirst().orElseThrow(SupplierDisputeService::missing);
    }
    @Transactional(readOnly=true)
    public List<Variance> variances(UUID tenant,UUID order) {
        return jdbc.query(VARIANCE_SQL+"""
            AND purchase_order_id=? ORDER BY created_at DESC,id DESC LIMIT 200
            """,SupplierDisputeService::mapVariance,tenant,order);
    }
    private void audit(UUID tenant,UUID actor,UUID variance,UUID claim,String event,
                       String note) {
        jdbc.update("""
            INSERT INTO erp_supplier_case_audit
              (id,tenant_id,variance_id,claim_id,actor_user_id,event_type,note)
            VALUES (?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,variance,claim,actor,event,note);
    }
    @Transactional
    public Variance report(UUID tenant,UUID actor,UUID orderId,Report req) {
        if(req==null || req.requestKey()==null || req.lineId()==null
            || !List.of("PO_SHORTAGE","ASN_SHORTAGE","OVERAGE_UNPOSTED").contains(req.kind())
            || ("ASN_SHORTAGE".equals(req.kind()))!=(req.asnId()!=null)
            || req.reportedExcessQuantity()<0 || req.reportedExcessQuantity()>1_000_000
            || ("OVERAGE_UNPOSTED".equals(req.kind())==(req.reportedExcessQuantity()==0)))
            throw bad("Invalid discrepancy kind, ASN or observed excess quantity");
        String explanation=reason(req.reason());
        tenantLock(tenant);
        List<UUID> previous=jdbc.queryForList("""
            SELECT id FROM erp_purchase_variance WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!previous.isEmpty()) {
            Variance old=variance(tenant,previous.getFirst());
            if(!old.orderId().equals(orderId.toString())
                || !old.lineId().equals(req.lineId().toString())
                || !old.kind().equals(req.kind())
                || !Objects.equals(old.asnId(),
                    req.asnId()==null?null:req.asnId().toString())
                || !old.reason().equals(explanation)
                || ("OVERAGE_UNPOSTED".equals(req.kind())
                     && old.varianceQuantity()!=req.reportedExcessQuantity())
                || !old.createdBy().equals(actor.toString()))
                throw conflict("Discrepancy request key was already used differently");
            return old;
        }
        OrderState order=orderLock(tenant,orderId);
        if(!List.of("APPROVED","RECEIVING","RECEIVED").contains(order.status()))
            throw conflict("Purchase order is not approved");
        Quantities line=quantities(tenant,orderId,req.lineId());
        int expected;
        int observed;
        long difference;
        switch(req.kind()) {
            case "PO_SHORTAGE" -> {
                if("RECEIVED".equals(order.status()))
                    throw conflict("Fully received purchase order cannot be short");
                expected=line.ordered();
                observed=Math.toIntExact(line.received());
                difference=(long)expected-observed;
            }
            case "ASN_SHORTAGE" -> {
                AsnQuantities asn=asnQuantities(tenant,orderId,req.lineId(),req.asnId());
                if(!"SHIPPED".equals(asn.status()))
                    throw conflict("ASN shortage requires a supplier-reported dispatch");
                expected=asn.quantity();
                observed=Math.toIntExact(asn.received());
                difference=(long)expected-observed;
            }
            case "OVERAGE_UNPOSTED" -> {
                expected=Math.toIntExact(line.ordered()-line.received());
                difference=req.reportedExcessQuantity();
                observed=Math.toIntExact((long)expected+difference);
            }
            default -> throw bad("Unknown discrepancy kind");
        }
        if(difference<=0 || difference>1_000_000)
            throw conflict("No positive reported discrepancy at the observation checkpoint");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_purchase_variance
              (id,tenant_id,request_key,purchase_order_id,purchase_order_line_id,asn_id,
               kind,expected_quantity,observed_quantity,variance_quantity,reason,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),orderId,req.lineId(),req.asnId(),
            req.kind(),expected,observed,(int)difference,explanation,actor);
        audit(tenant,actor,id,null,"VARIANCE_REPORTED",explanation);
        return variance(tenant,id);
    }
    @Transactional
    public Variance changeVariance(UUID tenant,UUID actor,UUID id,String action) {
        if(!List.of("review","close").contains(action))throw bad("Invalid discrepancy action");
        tenantLock(tenant);
        Variance prior=variance(tenant,id);
        if("review".equals(action)) {
            if("REVIEWED".equals(prior.state()))return prior;
            if(!"OPEN".equals(prior.state()))throw conflict("Discrepancy not open");
            if(prior.createdBy().equals(actor.toString()))
                throw conflict("A different Owner must review the discrepancy");
            jdbc.update("""
                UPDATE erp_purchase_variance
                SET state='REVIEWED',reviewed_by=?,reviewed_at=now(),updated_at=now()
                WHERE tenant_id=? AND id=? AND state='OPEN'
                """,actor,tenant,id);
            audit(tenant,actor,id,null,"VARIANCE_REVIEWED",null);
        } else {
            if("CLOSED".equals(prior.state()))return prior;
            if(!"REVIEWED".equals(prior.state()))
                throw conflict("Only a reviewed discrepancy may be closed");
            jdbc.update("""
                UPDATE erp_purchase_variance SET state='CLOSED',updated_at=now()
                WHERE tenant_id=? AND id=? AND state='REVIEWED'
                """,tenant,id);
            audit(tenant,actor,id,null,"VARIANCE_CLOSED",null);
        }
        return variance(tenant,id);
    }
    private static Claim mapClaim(ResultSet rs,int i) throws SQLException {
        UUID variance=rs.getObject("variance_id",UUID.class);
        UUID inspection=rs.getObject("inspection_id",UUID.class);
        UUID approved=rs.getObject("approved_by",UUID.class);
        return new Claim(rs.getObject("id",UUID.class).toString(),
            rs.getObject("purchase_order_id",UUID.class).toString(),
            rs.getObject("supplier_id",UUID.class).toString(),
            variance==null?null:variance.toString(),
            inspection==null?null:inspection.toString(),
            rs.getInt("requested_quantity"),rs.getString("remedy"),
            rs.getString("reason"),rs.getString("status"),
            rs.getObject("created_by",UUID.class).toString(),
            approved==null?null:approved.toString(),
            rs.getString("resolution_note"),instant(rs,"created_at"));
    }
    private static final String CLAIM_SQL = """
        SELECT id,purchase_order_id,supplier_id,variance_id,inspection_id,
          requested_quantity,remedy,reason,status,created_by,approved_by,
          resolution_note,created_at
        FROM erp_supplier_claim WHERE tenant_id=?
        """;
    private Claim claim(UUID tenant,UUID id) {
        return jdbc.query(CLAIM_SQL+" AND id=?",SupplierDisputeService::mapClaim,
            tenant,id).stream().findFirst().orElseThrow(SupplierDisputeService::missing);
    }
    @Transactional(readOnly=true)
    public List<Claim> claims(UUID tenant,UUID order) {
        return jdbc.query(CLAIM_SQL+"""
            AND purchase_order_id=? ORDER BY created_at DESC,id DESC LIMIT 200
            """,SupplierDisputeService::mapClaim,tenant,order);
    }
    private Source source(UUID tenant,UUID varianceId,UUID inspectionId) {
        if(varianceId!=null) {
            return jdbc.query("""
                SELECT v.purchase_order_id,o.supplier_id,v.variance_quantity,
                  v.state AS status
                FROM erp_purchase_variance v
                JOIN erp_purchase_order o ON o.tenant_id=v.tenant_id
                  AND o.id=v.purchase_order_id
                WHERE v.tenant_id=? AND v.id=?
                """,(rs,i)->new Source(rs.getObject("purchase_order_id",UUID.class).toString(),
                    rs.getObject("supplier_id",UUID.class).toString(),
                    rs.getLong("variance_quantity"),rs.getString("status")),
                tenant,varianceId).stream().findFirst().orElseThrow(SupplierDisputeService::missing);
        }
        return jdbc.query("""
            SELECT r.purchase_order_id,o.supplier_id,q.rejected_quantity,
              'INSPECTED' AS status
            FROM erp_purchase_receipt_qc q
            JOIN erp_purchase_receipt r ON r.tenant_id=q.tenant_id
              AND r.id=q.purchase_receipt_id
            JOIN erp_purchase_order o ON o.tenant_id=r.tenant_id
              AND o.id=r.purchase_order_id
            WHERE q.tenant_id=? AND q.id=?
            """,(rs,i)->new Source(rs.getObject("purchase_order_id",UUID.class).toString(),
                rs.getObject("supplier_id",UUID.class).toString(),
                rs.getLong("rejected_quantity"),rs.getString("status")),
            tenant,inspectionId).stream().findFirst().orElseThrow(SupplierDisputeService::missing);
    }
    @Transactional
    public Claim createClaim(UUID tenant,UUID actor,ClaimRequest req) {
        if(req==null || req.requestKey()==null
            || (req.varianceId()==null)==(req.inspectionId()==null)
            || !List.of("REPLACEMENT","CREDIT_REQUEST").contains(req.remedy())
            || req.requestedQuantity()<1 || req.requestedQuantity()>1_000_000)
            throw bad("Invalid supplier claim");
        String explanation=reason(req.reason());
        tenantLock(tenant);
        List<UUID> previous=jdbc.queryForList("""
            SELECT id FROM erp_supplier_claim WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!previous.isEmpty()) {
            Claim old=claim(tenant,previous.getFirst());
            if(!Objects.equals(old.varianceId(),
                req.varianceId()==null?null:req.varianceId().toString())
                || !Objects.equals(old.inspectionId(),
                req.inspectionId()==null?null:req.inspectionId().toString())
                || old.requestedQuantity()!=req.requestedQuantity()
                || !old.remedy().equals(req.remedy()) || !old.reason().equals(explanation)
                || !old.createdBy().equals(actor.toString()))
                throw conflict("Supplier claim idempotency key was reused differently");
            return old;
        }
        Source src=source(tenant,req.varianceId(),req.inspectionId());
        if(req.varianceId()!=null && !"REVIEWED".equals(src.status()))
            throw conflict("Discrepancy must be reviewed before creating a claim");
        if(req.requestedQuantity()>src.maxQuantity())
            throw conflict("Claim cannot exceed source observed variance or rejected QC units");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_supplier_claim
              (id,tenant_id,request_key,variance_id,inspection_id,purchase_order_id,
               supplier_id,requested_quantity,remedy,reason,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),req.varianceId(),req.inspectionId(),
            UUID.fromString(src.orderId()),UUID.fromString(src.supplierId()),
            req.requestedQuantity(),req.remedy(),explanation,actor);
        audit(tenant,actor,null,id,"CLAIM_DRAFTED",explanation);
        return claim(tenant,id);
    }
    private void verifyClaimEvidence(UUID tenant,Claim value) {
        if(value.varianceId()==null)return; // QC rejected quantity is immutable.
        Variance observation=variance(tenant,UUID.fromString(value.varianceId()));
        if(!"REVIEWED".equals(observation.state()))
            throw conflict("Discrepancy review no longer current");
        UUID order=UUID.fromString(observation.orderId());
        UUID line=UUID.fromString(observation.lineId());
        Quantities qty=quantities(tenant,order,line);
        long received="ASN_SHORTAGE".equals(observation.kind())
            ? asnQuantities(tenant,order,line,UUID.fromString(observation.asnId())).received()
            : qty.received();
        if(!"OVERAGE_UNPOSTED".equals(observation.kind())
            && received!=observation.observedQuantity())
            throw conflict("The actual receipt evidence changed since the shortage report");
        if("OVERAGE_UNPOSTED".equals(observation.kind())
            && (long)qty.ordered()-qty.received()!=observation.expectedQuantity())
            throw conflict("Available PO receiving capacity changed after overage observation");
    }
    @Transactional
    public Claim changeClaim(UUID tenant,UUID actor,UUID id,String action,String note) {
        if(!List.of("submit","approve","close","cancel").contains(action))
            throw bad("Unsupported claim action");
        String resolution="close".equals(action)?reason(note):null;
        tenantLock(tenant);
        Claim current=claim(tenant,id);
        String next;
        switch(action) {
            case "submit" -> {
                if(!"DRAFT".equals(current.status()))throw conflict("Claim not draft");
                next="SUBMITTED";
                verifyClaimEvidence(tenant,current);
            }
            case "approve" -> {
                if(!"SUBMITTED".equals(current.status())
                    || current.createdBy().equals(actor.toString()))
                    throw conflict("A different Owner must approve a submitted claim");
                next="APPROVED";
                verifyClaimEvidence(tenant,current);
            }
            case "close" -> {
                if(!"APPROVED".equals(current.status()))
                    throw conflict("Only approved claim may be closed");
                next="CLOSED";
            }
            case "cancel" -> {
                if(!List.of("DRAFT","SUBMITTED").contains(current.status()))
                    throw conflict("Only unapproved claim can be cancelled");
                next="CANCELLED";
            }
            default -> throw bad("Unsupported claim action");
        }
        jdbc.update("""
            UPDATE erp_supplier_claim SET status=?,updated_at=now(),
              approved_by=CASE WHEN ?='APPROVED' THEN ? ELSE approved_by END,
              approval_at=CASE WHEN ?='APPROVED' THEN now() ELSE approval_at END,
              resolution_note=CASE WHEN ?='CLOSED' THEN ? ELSE resolution_note END
            WHERE tenant_id=? AND id=? AND status=?
            """,next,next,actor,next,next,resolution,tenant,id,current.status());
        audit(tenant,actor,null,id,"CLAIM_"+next,resolution);
        return claim(tenant,id);
    }
    @Transactional(readOnly=true)
    public List<Event> events(UUID tenant,UUID varianceId,UUID claimId) {
        if((varianceId==null)==(claimId==null))
            throw bad("Provide a discrepancy or claim ID");
        if(varianceId!=null)variance(tenant,varianceId);else claim(tenant,claimId);
        return jdbc.query("""
            SELECT id,event_type,actor_user_id,note,created_at
            FROM erp_supplier_case_audit
            WHERE tenant_id=? AND ((?::uuid IS NOT NULL AND variance_id=?)
                               OR (?::uuid IS NOT NULL AND claim_id=?))
            ORDER BY created_at DESC,id DESC LIMIT 100
            """,(rs,i)->new Event(rs.getObject("id",UUID.class).toString(),
                rs.getString("event_type"),
                rs.getObject("actor_user_id",UUID.class).toString(),
                rs.getString("note"),instant(rs,"created_at")),
                tenant,varianceId,varianceId,claimId,claimId);
    }

    @Transactional(readOnly=true)
    public List<Performance> performance(UUID tenant) {
        return jdbc.query("""
            SELECT s.id,s.display_name,
              (SELECT COUNT(*) FROM erp_purchase_order o
                WHERE o.tenant_id=s.tenant_id AND o.supplier_id=s.id
                  AND o.status IN ('APPROVED','RECEIVING','RECEIVED'))::bigint AS order_count,
              COALESCE((SELECT SUM(l.ordered_quantity)
                FROM erp_purchase_order_line l
                JOIN erp_purchase_order o ON o.tenant_id=l.tenant_id
                  AND o.id=l.purchase_order_id
                WHERE o.tenant_id=s.tenant_id AND o.supplier_id=s.id
                  AND o.status IN ('APPROVED','RECEIVING','RECEIVED')),0)::bigint AS ordered_units,
              COALESCE((SELECT SUM(r.quantity)
                FROM erp_purchase_receipt r
                JOIN erp_purchase_order o ON o.tenant_id=r.tenant_id
                  AND o.id=r.purchase_order_id
                WHERE o.tenant_id=s.tenant_id AND o.supplier_id=s.id),0)::bigint AS received_units,
              COALESCE((SELECT SUM(q.rejected_quantity)
                FROM erp_purchase_receipt_qc q
                JOIN erp_purchase_receipt r ON r.tenant_id=q.tenant_id
                  AND r.id=q.purchase_receipt_id
                JOIN erp_purchase_order o ON o.tenant_id=r.tenant_id
                  AND o.id=r.purchase_order_id
                WHERE o.tenant_id=s.tenant_id AND o.supplier_id=s.id),0)::bigint AS rejected_units,
              (SELECT COUNT(*) FROM erp_purchase_variance v
                JOIN erp_purchase_order o ON o.tenant_id=v.tenant_id
                  AND o.id=v.purchase_order_id
                WHERE o.tenant_id=s.tenant_id AND o.supplier_id=s.id
                  AND v.state<>'CLOSED')::bigint AS open_discrepancies,
              (SELECT COUNT(*) FROM erp_supplier_claim c
                WHERE c.tenant_id=s.tenant_id AND c.supplier_id=s.id
                  AND c.status IN ('DRAFT','SUBMITTED','APPROVED'))::bigint AS open_claims,
              (SELECT COUNT(*) FROM erp_purchase_asn a
                JOIN erp_purchase_order o ON o.tenant_id=a.tenant_id
                  AND o.id=a.purchase_order_id
                WHERE o.tenant_id=s.tenant_id AND o.supplier_id=s.id
                  AND o.status IN ('APPROVED','RECEIVING')
                  AND a.status='SHIPPED' AND a.expected_arrival_date<CURRENT_DATE
                  AND a.quantity>(SELECT COALESCE(SUM(r.quantity),0)
                    FROM erp_purchase_receipt r
                    WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id))::bigint AS overdue_asns
            FROM erp_supplier s WHERE s.tenant_id=?
            ORDER BY s.supplier_code LIMIT 200
            """,(rs,i)->new Performance(rs.getObject("id",UUID.class).toString(),
                rs.getString("display_name"),rs.getLong("order_count"),
                rs.getLong("ordered_units"),rs.getLong("received_units"),
                rs.getLong("rejected_units"),rs.getLong("open_discrepancies"),
                rs.getLong("open_claims"),rs.getLong("overdue_asns")),tenant);
    }
}
