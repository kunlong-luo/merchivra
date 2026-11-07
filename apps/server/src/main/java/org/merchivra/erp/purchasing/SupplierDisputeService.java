package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    /** A source is claimable only if the entire tenant has no prior claim,
     * including cancelled records (V45 partial unique indexes are permanent).
     */
    public record ClaimSource(String sourceType,String sourceId,int maxQuantity,
                              String createdAt) {}
    private record Timed<T>(T item,String createdAt) {}
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
    private static void checkFilter(String filter) {
        if(!List.of("ACTIONABLE","ALL").contains(filter))
            throw bad("Dispute filter must be ACTIONABLE or ALL");
    }
    private static String[] cursor(String raw,String...scope) {
        String[] parsed=LocalKeysetPage.decode(raw,scope.length+2);
        if(parsed!=null) {
            for(int i=0;i<scope.length;i++)
                if(!scope[i].equals(parsed[i]))throw bad("Cursor scope mismatch");
        }
        return parsed;
    }
    private static OffsetDateTime cursorTime(String raw) {
        try {
            return OffsetDateTime.ofInstant(Instant.parse(raw),ZoneOffset.UTC);
        } catch(Exception e) { throw bad("Invalid dispute pagination timestamp"); }
    }
    private static UUID cursorUuid(String raw) {
        try {return UUID.fromString(raw);}
        catch(IllegalArgumentException e) {throw bad("Invalid dispute pagination ID");}
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
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Variance> variancesPage(UUID tenant,UUID order,
        String filter,int requested,String rawCursor) {
        checkFilter(filter);
        int limit=LocalKeysetPage.limit(requested);
        String[] after=cursor(rawCursor,tenant.toString(),order.toString(),filter);
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(order);
        String where="ACTIONABLE".equals(filter)
            ?" AND state IN ('OPEN','REVIEWED')":"";
        if(after!=null) {
            where+=" AND (created_at,id)<(?,?)";
            args.add(cursorTime(after[3]));args.add(cursorUuid(after[4]));
        }
        args.add(limit+1);
        List<Variance> rows=jdbc.query(VARIANCE_SQL+
            " AND purchase_order_id=?"+where+
            " ORDER BY created_at DESC,id DESC LIMIT ?",
            SupplierDisputeService::mapVariance,args.toArray());
        return LocalKeysetPage.pack(rows,limit,v->LocalKeysetPage.encode(
            tenant.toString(),order.toString(),filter,v.createdAt(),v.id()));
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
            || req.kind()==null
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
        // V96: UI pages are never a valid full-order duplicate check.
        // Guard the V45 unique source rule explicitly for an actionable 409.
        List<UUID> sourceConflict=jdbc.queryForList("""
            SELECT id FROM erp_purchase_variance
            WHERE tenant_id=? AND purchase_order_line_id=? AND kind=?
              AND asn_id IS NOT DISTINCT FROM ?::uuid
            """,UUID.class,tenant,req.lineId(),req.kind(),req.asnId());
        if(!sourceConflict.isEmpty())
            throw conflict("This line / ASN discrepancy is already reported");
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
    private void verifyVarianceSnapshot(UUID tenant,Variance observation) {
        UUID order=UUID.fromString(observation.orderId());
        UUID line=UUID.fromString(observation.lineId());
        OrderState status=orderLock(tenant,order);
        if(!List.of("APPROVED","RECEIVING","RECEIVED").contains(status.status()))
            throw conflict("Source purchase order is no longer approved");
        Quantities qty=quantities(tenant,order,line);
        if("PO_SHORTAGE".equals(observation.kind())) {
            if("RECEIVED".equals(status.status())
                || qty.ordered()!=observation.expectedQuantity()
                || qty.received()!=observation.observedQuantity())
                throw conflict("Source purchase shortage has changed since observation");
        } else if("ASN_SHORTAGE".equals(observation.kind())) {
            AsnQuantities a=asnQuantities(tenant,order,line,
                UUID.fromString(observation.asnId()));
            if(!"SHIPPED".equals(a.status())
                || a.quantity()!=observation.expectedQuantity()
                || a.received()!=observation.observedQuantity())
                throw conflict("Source ASN shipment/receipt evidence changed");
        } else if((long)qty.ordered()-qty.received()!=observation.expectedQuantity())
            throw conflict("PO receiving capacity changed after unposted overage report");
    }
    @Transactional
    public Variance changeVariance(UUID tenant,UUID actor,UUID id,String action) {
        if(action==null || !List.of("review","close").contains(action))
            throw bad("Invalid discrepancy action");
        tenantLock(tenant);
        Variance prior=variance(tenant,id);
        if("review".equals(action)) {
            if("REVIEWED".equals(prior.state()))return prior;
            if(!"OPEN".equals(prior.state()))throw conflict("Discrepancy not open");
            if(prior.createdBy().equals(actor.toString()))
                throw conflict("A different Owner must review the discrepancy");
            verifyVarianceSnapshot(tenant,prior);
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
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Claim> claimsPage(UUID tenant,UUID order,
        String filter,int requested,String rawCursor) {
        checkFilter(filter);
        int limit=LocalKeysetPage.limit(requested);
        String[] after=cursor(rawCursor,tenant.toString(),order.toString(),filter);
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(order);
        String where="ACTIONABLE".equals(filter)
            ?" AND status IN ('DRAFT','SUBMITTED','APPROVED')":"";
        if(after!=null) {
            where+=" AND (created_at,id)<(?,?)";
            args.add(cursorTime(after[3]));args.add(cursorUuid(after[4]));
        }
        args.add(limit+1);
        List<Claim> rows=jdbc.query(CLAIM_SQL+
            " AND purchase_order_id=?"+where+
            " ORDER BY created_at DESC,id DESC LIMIT ?",
            SupplierDisputeService::mapClaim,args.toArray());
        return LocalKeysetPage.pack(rows,limit,c->LocalKeysetPage.encode(
            tenant.toString(),order.toString(),filter,c.createdAt(),c.id()));
    }

    /** Includes reviewed variances and confirmed QC rejected quantities
     * without relying on any of the capped variance/receipt/claim lists.
     * The permanent V45 claim unique indexes are respected across ALL states.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<ClaimSource> claimSourcesPage(UUID tenant,UUID order,
        int requested,String rawCursor) {
        int limit=LocalKeysetPage.limit(requested);
        String[] after=cursor(rawCursor,tenant.toString(),order.toString(),"SOURCES");
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(order);args.add(tenant);args.add(tenant);
        args.add(order);args.add(tenant);
        String where="";
        if(after!=null) {
            where=" WHERE (created_at,source_id) < (?,?)";
            args.add(cursorTime(after[3]));args.add(cursorUuid(after[4]));
        }
        args.add(limit+1);
        List<ClaimSource> rows=jdbc.query("""
            WITH sources AS (
              SELECT 'VARIANCE'::text AS source_type,v.id AS source_id,
                     v.variance_quantity AS max_quantity,v.created_at
              FROM erp_purchase_variance v
              WHERE v.tenant_id=? AND v.purchase_order_id=? AND v.state='REVIEWED'
                AND NOT EXISTS (SELECT 1 FROM erp_supplier_claim c
                    WHERE c.tenant_id=? AND c.variance_id=v.id)
              UNION ALL
              SELECT 'QC'::text AS source_type,q.id AS source_id,
                     q.rejected_quantity AS max_quantity,q.created_at
              FROM erp_purchase_receipt_qc q
              JOIN erp_purchase_receipt r ON r.tenant_id=q.tenant_id
                    AND r.id=q.purchase_receipt_id
              WHERE q.tenant_id=? AND r.purchase_order_id=?
                AND q.rejected_quantity>0
                AND NOT EXISTS (SELECT 1 FROM erp_supplier_claim c
                    WHERE c.tenant_id=? AND c.inspection_id=q.id)
            )
            SELECT source_type,source_id,max_quantity,created_at FROM sources
            """+where+" ORDER BY created_at DESC,source_id DESC LIMIT ?",
            (rs,i)->new ClaimSource(rs.getString("source_type"),
                rs.getObject("source_id",UUID.class).toString(),
                rs.getInt("max_quantity"),instant(rs,"created_at")),
            args.toArray());
        return LocalKeysetPage.pack(rows,limit,c->LocalKeysetPage.encode(
            tenant.toString(),order.toString(),"SOURCES",c.createdAt(),c.sourceId()));
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
            || req.remedy()==null
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
        if(req.varianceId()!=null) {
            if(!"REVIEWED".equals(src.status()))
                throw conflict("Discrepancy must be reviewed before creating a claim");
            verifyVarianceSnapshot(tenant,variance(tenant,req.varianceId()));
        }
        // This check covers sources beyond the user's current page, while
        // the V45 database partial unique indexes protect concurrent writers.
        Integer already=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_supplier_claim
            WHERE tenant_id=? AND (variance_id=? OR inspection_id=?)
            """,Integer.class,tenant,req.varianceId(),req.inspectionId());
        if(already!=null&&already>0)
            throw conflict("This discrepancy or QC inspection already has a claim");
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
        verifyVarianceSnapshot(tenant,observation);
    }
    @Transactional
    public Claim changeClaim(UUID tenant,UUID actor,UUID id,String action,String note) {
        if(action==null || !List.of("submit","approve","close","cancel").contains(action))
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
    public LocalKeysetPage.Page<Event> eventsPage(UUID tenant,UUID sourceId,
        String type,int requested,String rawCursor) {
        if(!"variances".equals(type)&&!"claims".equals(type))
            throw bad("Invalid supplier case type");
        if("variances".equals(type))variance(tenant,sourceId);
        else claim(tenant,sourceId);
        int limit=LocalKeysetPage.limit(requested);
        String[] after=cursor(rawCursor,tenant.toString(),type,sourceId.toString());
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(sourceId);
        String where="";
        if(after!=null) {
            where=" AND (created_at,id)<(?,?)";
            args.add(cursorTime(after[3]));args.add(cursorUuid(after[4]));
        }
        args.add(limit+1);
        List<Event> rows=jdbc.query("""
            SELECT id,event_type,actor_user_id,note,created_at
            FROM erp_supplier_case_audit
            WHERE tenant_id=? AND """+
            ("variances".equals(type)?"variance_id=?":"claim_id=?")+
            where+" ORDER BY created_at DESC,id DESC LIMIT ?",
            (rs,i)->new Event(rs.getObject("id",UUID.class).toString(),
                rs.getString("event_type"),
                rs.getObject("actor_user_id",UUID.class).toString(),
                rs.getString("note"),instant(rs,"created_at")),args.toArray());
        return LocalKeysetPage.pack(rows,limit,e->LocalKeysetPage.encode(
            tenant.toString(),type,sourceId.toString(),e.createdAt(),e.id()));
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
