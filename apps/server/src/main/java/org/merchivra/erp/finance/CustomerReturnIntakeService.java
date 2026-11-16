package org.merchivra.erp.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * V172: distinct LOCAL physical-return intake candidates per warehouse parcel.
 * Reviewer attestation is not independent authenticated WMS/FBA proof;
 * never posts inventory or COGS and never edits V171/V63 evidence.
 */
@Service
public class CustomerReturnIntakeService {
    private static final int PAGE_SIZE=50;
    private static final Set<String> CONDITIONS=Set.of(
        "SELLABLE_CLAIMED","UNSELLABLE_CLAIMED","UNKNOWN");
    private final JdbcTemplate jdbc;
    private final ReturnReceiptEvidenceService receipts;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public CustomerReturnIntakeService(JdbcTemplate jdbc,
        ReturnReceiptEvidenceService receipts, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.receipts=receipts;
        this.tx=new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTx=new TransactionTemplate(manager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record Request(UUID claimId,UUID warehouseId,String receiptReference,
        int quantity,String condition,String note) {}
    public record Decision(String reason) {}
    public record Intake(String id,String claimId,String itemId,String warehouseId,
        String sourceReference,int quantity,String condition,String status,
        String requestedBy,String decidedBy,String createdAt,
        boolean evidenceCurrent,boolean externalWmsVerified,
        boolean inventoryPosted,boolean cogsReversalEligible,
        String actualCogsReversal) {}
    public record Coverage(String claimId,String itemId,int manualClaimedQuantity,
        long pendingQuantity,long locallyAttestedQuantity,long remainingQuantity,
        boolean evidenceCurrent,boolean externalWmsVerified,
        boolean inventoryPosted,boolean verifiedCogsReversal) {}
    public record Page(String eventId,List<Coverage> coverage,List<Intake> receipts,
        boolean hasMore,String nextBeforeAt,String nextBeforeId,
        boolean externalWarehouseReconciled,boolean realizedNetProfitVerified) {}
    public record Audit(String action,String actorId,String note,String occurredAt) {}
    private record Row(UUID id,UUID event,UUID order,String item,UUID claim,UUID warehouse,
        String reference,int quantity,String condition,String state,String fingerprint,
        UUID maker,UUID checker,OffsetDateTime at) {}
    private record Totals(String item,long pending,long attested) {}
    private record Snapshot(List<Totals> totals,List<Row> rows,boolean more) {}

    static void validate(Request input) {
        if(input==null||input.claimId()==null||input.warehouseId()==null
            ||input.quantity()<1||input.quantity()>1000000
            ||input.condition()==null||!CONDITIONS.contains(input.condition()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide V171 claim, warehouse, valid positive quantity and disposition");
        ReturnReceiptEvidenceService.reference(input.receiptReference());
        RefundRecoveryService.reason(input.note());
    }
    static void validateCursor(OffsetDateTime at,UUID id) {
        if((at==null)!=(id==null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A complete timestamp+UUID cursor is required");
    }
    static String hash(ReturnReceiptEvidenceService.Claim claim) {
        return RefundRecoveryService.hash(claim.id()+"|"+claim.eventId()+"|"+
            claim.orderId()+"|"+claim.itemId()+"|"+claim.reviewRevision()+"|"+
            claim.claimedQuantity()+"|"+claim.condition()+"|"+claim.warehouseId()+"|"+
            claim.evidenceReference()+"|"+claim.requestedBy()+"|"+claim.decidedBy());
    }
    static void eligible(ReturnReceiptEvidenceService.Claim claim,int quantity) {
        if(!"REVIEWED_MANUAL".equals(claim.state())||!claim.evidenceCurrent()
            ||quantity<1||quantity>claim.claimedQuantity())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Current V171 independently reviewed manual return claim required");
    }
    private ReturnReceiptEvidenceService.Claim claim(List<ReturnReceiptEvidenceService.Claim> all,
        UUID id) {
        return all.stream().filter(x->x.id().equals(id.toString())).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No tenant-scoped V171 claim under this refund"));
    }
    private static Row row(ResultSet r,int i)throws SQLException {
        return new Row(r.getObject("id",UUID.class),r.getObject("event_id",UUID.class),
            r.getObject("sales_order_id",UUID.class),r.getString("external_item_id"),
            r.getObject("claim_id",UUID.class),r.getObject("warehouse_id",UUID.class),
            r.getString("receipt_reference"),r.getInt("received_quantity"),
            r.getString("condition_code"),r.getString("state"),
            r.getString("source_fingerprint"),r.getObject("requested_by",UUID.class),
            r.getObject("decided_by",UUID.class),
            r.getObject("requested_at",OffsetDateTime.class));
    }
    private static Intake display(Row row,Map<String,ReturnReceiptEvidenceService.Claim> claims) {
        var c=claims.get(row.claim().toString());
        boolean current=c!=null&&"REVIEWED_MANUAL".equals(c.state())
            &&c.evidenceCurrent()&&row.fingerprint().equals(hash(c))
            &&row.order().toString().equals(c.orderId())&&row.item().equals(c.itemId());
        return new Intake(row.id().toString(),row.claim().toString(),row.item(),
            row.warehouse().toString(),row.reference(),row.quantity(),
            row.condition(),current?row.state():"STALE",row.maker().toString(),
            row.checker()==null?null:row.checker().toString(),
            row.at().toInstant().toString(),current,false,false,false,null);
    }
    public Page report(UUID tenant,UUID mp,UUID event,OffsetDateTime before,UUID beforeId) {
        validateCursor(before,beforeId);
        // V171 in its own read transaction; missing V63 source evidence must
        // never poison the aggregate's separate REPEATABLE_READ snapshot.
        var claims=receipts.list(tenant,mp,event);
        var byId=claims.stream().collect(Collectors.toMap(
            ReturnReceiptEvidenceService.Claim::id,x->x,(a,b)->a));
        Snapshot snapshot=readTx.execute(t->{
            List<Totals> totals=jdbc.query("""
                SELECT external_item_id,
                  COALESCE(sum(received_quantity) FILTER
                    (WHERE state='PENDING'),0) AS pending,
                  COALESCE(sum(received_quantity) FILTER
                    (WHERE state='ATTESTED_LOCAL'),0) AS attested
                FROM finance_customer_return_intake_v97
                WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
                GROUP BY external_item_id
                """,(r,i)->new Totals(r.getString("external_item_id"),
                    r.getLong("pending"),r.getLong("attested")),tenant,mp,event);
            String query="""
                SELECT * FROM finance_customer_return_intake_v97
                WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
                """+(before==null?"":"""
                 AND (requested_at,id)<(?,?)
                """)+"""
                ORDER BY requested_at DESC,id DESC LIMIT 51
                """;
            List<Row> all=before==null
                ?jdbc.query(query,CustomerReturnIntakeService::row,tenant,mp,event)
                :jdbc.query(query,CustomerReturnIntakeService::row,tenant,mp,event,before,beforeId);
            return new Snapshot(totals,List.copyOf(all.subList(0,Math.min(50,all.size()))),
                all.size()>PAGE_SIZE);
        });
        var sum=snapshot.totals().stream().collect(Collectors.toMap(
            x->x.item(),x->x));
        var coverage=new ArrayList<Coverage>();
        for(var claim:claims) {
            Totals total=sum.get(claim.itemId());
            long pending=total==null?0:total.pending(),attested=total==null?0:total.attested();
            // A negative remainder is not allowed to masquerade as full coverage
            // after a source quantity revision. Stale claims are always held.
            long remaining=Math.max(0,claim.claimedQuantity()-pending-attested);
            coverage.add(new Coverage(claim.id(),claim.itemId(),
                claim.claimedQuantity(),pending,attested,remaining,
                claim.evidenceCurrent()&&"REVIEWED_MANUAL".equals(claim.state()),
                false,false,false));
        }
        var events=snapshot.rows().stream().map(x->display(x,byId)).toList();
        Row last=snapshot.more()?snapshot.rows().getLast():null;
        return new Page(event.toString(),List.copyOf(coverage),events,
            snapshot.more(),last==null?null:last.at().toInstant().toString(),
            last==null?null:last.id().toString(),false,false);
    }
    private void lock(UUID tenant,UUID mp,UUID event,UUID order,UUID claimId) {
        if(jdbc.queryForList("""
            SELECT id FROM sales_order WHERE tenant_id=? AND seller_marketplace_id=?
              AND id=? AND data_origin='LIVE' FOR UPDATE
            """,UUID.class,tenant,mp,order).size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"LIVE order changed");
        if(jdbc.queryForList("""
            SELECT id FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
              AND category='PRODUCT_REFUND' FOR UPDATE
            """,UUID.class,tenant,mp,event).size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"LIVE refund changed");
        if(jdbc.queryForList("""
            SELECT id FROM finance_return_receipt_claim_v96
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
              AND id=? AND state='REVIEWED_MANUAL' FOR UPDATE
            """,UUID.class,tenant,mp,event,claimId).size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"V171 claim changed");
    }
    private ReturnReceiptEvidenceService.Claim current(UUID tenant,UUID mp,UUID event,
        UUID claimId,String digest,int quantity) {
        var now=claim(receipts.list(tenant,mp,event),claimId);
        eligible(now,quantity);
        if(!digest.equals(hash(now)))throw new ResponseStatusException(HttpStatus.CONFLICT,
            "V63/V171 source changed; refresh physical intake evidence");
        return now;
    }
    Intake detail(UUID tenant,UUID mp,UUID event,UUID id) {
        var claims=receipts.list(tenant,mp,event).stream().collect(Collectors.toMap(
            ReturnReceiptEvidenceService.Claim::id,x->x,(a,b)->a));
        var found=jdbc.query("""
            SELECT * FROM finance_customer_return_intake_v97
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND id=?
            """,CustomerReturnIntakeService::row,tenant,mp,event,id);
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return display(found.getFirst(),claims);
    }
    private void audit(UUID tenant,UUID id,UUID actor,String action,String note) {
        jdbc.update("""
            INSERT INTO finance_customer_return_intake_audit_v97
                (id,tenant_id,intake_id,actor_user_id,action,note)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,action,note);
    }
    public Intake request(UUID tenant,UUID mp,UUID event,UUID actor,Request input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        validate(input);
        var before=claim(receipts.list(tenant,mp,event),input.claimId());
        eligible(before,input.quantity());
        String digest=hash(before);
        UUID made=tx.execute(t->{
            UUID order=UUID.fromString(before.orderId());
            lock(tenant,mp,event,order,input.claimId());
            current(tenant,mp,event,input.claimId(),digest,input.quantity());
            Integer valid=jdbc.queryForObject("""
                SELECT count(*) FROM erp_local_warehouse
                WHERE tenant_id=? AND id=? AND status='ACTIVE'
                """,Integer.class,tenant,input.warehouseId());
            if(valid==null||valid!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An active tenant-owned local warehouse must receive this parcel");
            Long reserved=jdbc.queryForObject("""
                SELECT COALESCE(sum(received_quantity),0) FROM finance_customer_return_intake_v97
                WHERE tenant_id=? AND event_id=? AND external_item_id=?
                  AND state IN ('PENDING','ATTESTED_LOCAL')
                """,Long.class,tenant,event,before.itemId());
            if(reserved==null||reserved+input.quantity()>before.claimedQuantity())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "All active parcel receipts together exceed independently reviewed V171 quantity");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM finance_customer_return_intake_v97
                WHERE tenant_id=? AND warehouse_id=? AND receipt_reference=?
                """,Integer.class,tenant,input.warehouseId(),input.receiptReference());
            if(existing!=null&&existing>0)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Parcel receipt reference already exists at this warehouse");
            UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finance_customer_return_intake_v97
                  (id,tenant_id,seller_marketplace_id,event_id,sales_order_id,
                   external_item_id,claim_id,warehouse_id,receipt_reference,
                   received_quantity,condition_code,source_fingerprint,requested_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,mp,event,order,before.itemId(),input.claimId(),
                input.warehouseId(),input.receiptReference(),input.quantity(),
                input.condition(),digest,actor);
            audit(tenant,id,actor,"REQUESTED",RefundRecoveryService.reason(input.note()));
            return id;
        });
        return detail(tenant,mp,event,made);
    }
    public Intake decide(UUID tenant,UUID mp,UUID event,UUID id,UUID actor,
        boolean approve,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=RefundRecoveryService.reason(reason);
        var claims=receipts.list(tenant,mp,event);
        // Lock in the same order as V171 (order -> refund -> claim -> intake).
        var rows=jdbc.query("""
            SELECT * FROM finance_customer_return_intake_v97
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND id=?
            """,CustomerReturnIntakeService::row,tenant,mp,event,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Row before=rows.getFirst();
        var claim=claim(claims,before.claim());
        eligible(claim,before.quantity());
        UUID decided=tx.execute(t->{
            lock(tenant,mp,event,before.order(),before.claim());
            var found=jdbc.query("""
                SELECT * FROM finance_customer_return_intake_v97
                WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND id=?
                FOR UPDATE
                """,CustomerReturnIntakeService::row,tenant,mp,event,id);
            if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var row=found.getFirst();
            if(!"PENDING".equals(row.state()))throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Parcel already independently decided");
            RefundRecoveryService.requireIndependent(row.maker(),actor);
            if(actor.toString().equals(claim.requestedBy())
                ||actor.toString().equals(claim.decidedBy()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Parcel checker cannot author or decide the underlying V171 claim");
            current(tenant,mp,event,before.claim(),row.fingerprint(),row.quantity());
            if(!row.order().equals(before.order())||!row.item().equals(before.item())
                ||!row.fingerprint().equals(before.fingerprint()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Parcel was changed");
            Integer v34v35=jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM finance_refund_item_review
                   WHERE tenant_id=? AND event_id=? AND external_item_id=?
                     AND reviewed_by=?)
                  +(SELECT count(*) FROM finance_refund_amount_allocation
                    WHERE tenant_id=? AND event_id=? AND external_item_id=?
                      AND reviewed_by=?)
                """,Integer.class,tenant,event,row.item(),actor,
                tenant,event,row.item(),actor);
            if(v34v35==null||v34v35!=0)throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,"Parcel checker must be independent of V34/V35 authors");
            String state=approve?"ATTESTED_LOCAL":"REJECTED";
            jdbc.update("""
                UPDATE finance_customer_return_intake_v97
                SET state=?,decided_by=?,decided_at=now(),decision_reason=?
                WHERE tenant_id=? AND id=? AND state='PENDING'
                """,state,actor,note,tenant,id);
            audit(tenant,id,actor,state,note);
            return id;
        });
        return detail(tenant,mp,event,decided);
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID event,UUID id) {
        receipts.list(tenant,mp,event);
        Integer found=jdbc.queryForObject("""
            SELECT count(*) FROM finance_customer_return_intake_v97
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND id=?
            """,Integer.class,tenant,mp,event,id);
        if(found==null||found!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return jdbc.query("""
            SELECT action,actor_user_id,note,occurred_at
            FROM finance_customer_return_intake_audit_v97
            WHERE tenant_id=? AND intake_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),r.getObject(2,UUID.class).toString(),
                r.getString(3),r.getObject(4,OffsetDateTime.class).toInstant().toString()),
            tenant,id);
    }
}
