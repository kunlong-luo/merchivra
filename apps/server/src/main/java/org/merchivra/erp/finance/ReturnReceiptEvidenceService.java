package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * V171: manually claimed customer-return physical receipt evidence. Independent
 * review is NOT proof of warehouse intake, product saleability or COGS reversal.
 * Never writes V39 stock ledger, V64 profit or external Amazon records.
 */
@Service
public class ReturnReceiptEvidenceService {
    private final JdbcTemplate jdbc;
    private final RefundRecoveryService recovery;
    private final TransactionTemplate tx;
    private static final Set<String> CONDITIONS=Set.of(
        "SELLABLE_CLAIMED","UNSELLABLE_CLAIMED","UNKNOWN");
    public ReturnReceiptEvidenceService(JdbcTemplate jdbc,
        RefundRecoveryService recovery,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.recovery=recovery;
        this.tx=new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record Claim(String id,String eventId,String itemId,String orderId,
        int claimedQuantity,String condition,String warehouseId,String evidenceReference,
        String state,int reviewRevision,String requestedBy,String decidedBy,
        String requestedAt,boolean evidenceCurrent,boolean physicalReceiptVerified,
        boolean saleabilityVerified,boolean cogsReversalEligible,
        String actualCogsReversal) {}
    public record Audit(String action,String actorId,String note,String occurredAt) {}
    public record Request(String itemId,int quantity,String condition,
        UUID localWarehouseId,String evidenceReference,String note) {}
    private record Row(UUID id,UUID event,UUID order,String item,int quantity,
        String condition,UUID warehouse,String reference,String state,int revision,
        String fingerprint,UUID maker,UUID reviewer,OffsetDateTime at) {}

    static String reference(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A safe 8..160-character evidence reference is required");
        return value;
    }
    static void validate(Request request) {
        if(request==null||request.itemId()==null||request.itemId().isBlank()
            ||request.itemId().length()>160||request.quantity()<1
            ||request.quantity()>1000000||!CONDITIONS.contains(request.condition()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "One valid order item, positive quantity and condition required");
        reference(request.evidenceReference());
        RefundRecoveryService.reason(request.note());
    }
    private static RefundRecoveryEngine.Item item(RefundRecoveryService.Status current,
        String itemId) {
        return current.exposure().items().stream()
            .filter(i->i.itemId().equals(itemId))
            .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,
                "Item not present in current reviewed V63 refund evidence"));
    }
    private static String fingerprint(RefundRecoveryService.Status current,
        RefundRecoveryEngine.Item line) {
        return RefundRecoveryService.hash(current.eventId()+"|"+current.orderId()
            +"|"+current.revision()+"|"+current.exposure().currency()
            +"|"+current.exposure().signedRefundTotal()+"|"+line.itemId()
            +"|"+line.sku()+"|"+line.refundedQuantity()+"|"+line.disposition()
            +"|"+line.signedRefund()+"|"+line.historicalCostExposure()
            +"|"+line.claimedRestockCostSensitivity());
    }
    static void requireReviewed(RefundRecoveryService.Status status,
        RefundRecoveryEngine.Item line,int quantity) {
        if(!"REVIEWED".equals(status.state())||!status.evidenceCurrent()
            ||!"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(status.exposure().status())
            ||!"RESTOCKED_CLAIMED".equals(line.disposition())
            ||quantity>line.refundedQuantity())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Current independently reviewed V63 restock claim and quantity required");
    }
    private static Row row(java.sql.ResultSet rs,int i)throws java.sql.SQLException {
        return new Row(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),
            rs.getObject("sales_order_id",UUID.class),rs.getString("external_item_id"),
            rs.getInt("claimed_quantity"),rs.getString("condition_code"),
            rs.getObject("local_warehouse_id",UUID.class),
            rs.getString("evidence_reference"),rs.getString("state"),
            rs.getInt("source_review_revision"),rs.getString("source_fingerprint"),
            rs.getObject("requested_by",UUID.class),rs.getObject("decided_by",UUID.class),
            rs.getObject("requested_at",OffsetDateTime.class));
    }
    private List<Row> load(UUID tenant,UUID mp,UUID event) {
        return jdbc.query("""
            SELECT c.* FROM finance_return_receipt_claim_v96 c
            WHERE c.tenant_id=? AND c.seller_marketplace_id=? AND c.event_id=?
            ORDER BY c.requested_at DESC,c.id DESC LIMIT 100
            """,ReturnReceiptEvidenceService::row,tenant,mp,event);
    }
    private boolean exists(UUID tenant,UUID mp,UUID event) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM finance_source_event f
            JOIN tenant t ON t.id=f.tenant_id AND t.status='ACTIVE'
            WHERE f.tenant_id=? AND f.seller_marketplace_id=? AND f.id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND f.category='PRODUCT_REFUND'
            """,Integer.class,tenant,mp,event);
        return count!=null&&count==1;
    }
    private Claim view(Row row,RefundRecoveryService.Status current) {
        boolean evidence=false;
        if(current!=null&&"REVIEWED".equals(current.state())
            &&current.evidenceCurrent()&&current.revision()==row.revision()
            &&row.order().toString().equals(current.orderId())) {
            var line=current.exposure().items().stream()
                .filter(i->i.itemId().equals(row.item())).findFirst();
            evidence=line.isPresent()&&"RESTOCKED_CLAIMED".equals(line.get().disposition())
                &&row.quantity()<=line.get().refundedQuantity()
                &&row.fingerprint().equals(fingerprint(current,line.get()));
        }
        return new Claim(row.id().toString(),row.event().toString(),row.item(),
            row.order().toString(),row.quantity(),row.condition(),
            row.warehouse()==null?null:row.warehouse().toString(),row.reference(),
            evidence?row.state():"STALE",row.revision(),row.maker().toString(),
            row.reviewer()==null?null:row.reviewer().toString(),
            row.at().toInstant().toString(),evidence,false,false,false,null);
    }
    public List<Claim> list(UUID tenant,UUID mp,UUID event) {
        if(!exists(tenant,mp,event))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Row> rows=load(tenant,mp,event);
        if(rows.isEmpty())return List.of();
        RefundRecoveryService.Status current;
        try {current=recovery.preview(tenant,mp,event);}
        catch(ResponseStatusException err) {
            if(err.getStatusCode()!=HttpStatus.CONFLICT&&err.getStatusCode()!=HttpStatus.NOT_FOUND)
                throw err;
            current=null;
        }
        final var snapshot=current;
        return rows.stream().map(x->view(x,snapshot)).toList();
    }
    private void lockSource(UUID tenant,UUID mp,UUID event,UUID order) {
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
    }
    private void assertSame(UUID tenant,UUID mp,UUID event,String itemId,
        RefundRecoveryService.Status before,RefundRecoveryEngine.Item line) {
        var now=recovery.preview(tenant,mp,event);
        var currentLine=item(now,itemId);
        requireReviewed(now,currentLine,line.refundedQuantity());
        if(!now.orderId().equals(before.orderId())||now.revision()!=before.revision()
            ||!fingerprint(now,currentLine).equals(fingerprint(before,line)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund V34/V35/COGS evidence changed, refresh before submitting");
    }
    private void audit(UUID tenant,UUID id,UUID actor,String action,String note) {
        jdbc.update("""
            INSERT INTO finance_return_receipt_audit_v96
              (id,tenant_id,claim_id,actor_user_id,action,note)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,action,note);
    }
    public Claim request(UUID tenant,UUID mp,UUID event,UUID actor,Request input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        validate(input);
        if(!exists(tenant,mp,event))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var before=recovery.preview(tenant,mp,event);
        var line=item(before,input.itemId());
        requireReviewed(before,line,input.quantity());
        UUID id=tx.execute(t->{
            UUID order=UUID.fromString(before.orderId());
            lockSource(tenant,mp,event,order);
            assertSame(tenant,mp,event,input.itemId(),before,line);
            if(input.localWarehouseId()!=null) {
                Integer valid=jdbc.queryForObject("""
                    SELECT count(*) FROM erp_local_warehouse
                    WHERE tenant_id=? AND id=? AND status='ACTIVE'
                    """,Integer.class,tenant,input.localWarehouseId());
                if(valid==null||valid!=1)throw new ResponseStatusException(
                    HttpStatus.CONFLICT,"Not an active local warehouse");
            }
            Integer active=jdbc.queryForObject("""
                SELECT count(*) FROM finance_return_receipt_claim_v96
                WHERE tenant_id=? AND event_id=? AND external_item_id=?
                  AND state IN ('PENDING','REVIEWED_MANUAL')
                """,Integer.class,tenant,event,input.itemId());
            if(active!=null&&active>0)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"An active receipt claim already exists");
            UUID created=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finance_return_receipt_claim_v96
                  (id,tenant_id,seller_marketplace_id,event_id,sales_order_id,
                   external_item_id,source_review_revision,source_fingerprint,
                   claimed_quantity,condition_code,local_warehouse_id,
                   evidence_reference,requested_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,created,tenant,mp,event,order,input.itemId(),before.revision(),
                fingerprint(before,line),input.quantity(),input.condition(),
                input.localWarehouseId(),input.evidenceReference(),actor);
            audit(tenant,created,actor,"REQUESTED",
                RefundRecoveryService.reason(input.note()));
            return created;
        });
        return list(tenant,mp,event).stream().filter(x->x.id().equals(id.toString()))
            .findFirst().orElseThrow();
    }
    public Claim decide(UUID tenant,UUID mp,UUID event,UUID claim,UUID actor,
        boolean approve,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=RefundRecoveryService.reason(reason);
        if(!exists(tenant,mp,event))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var before=recovery.preview(tenant,mp,event);
        UUID result=tx.execute(t->{
            UUID order=UUID.fromString(before.orderId());
            lockSource(tenant,mp,event,order);
            var claims=jdbc.query("""
                SELECT * FROM finance_return_receipt_claim_v96
                WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND id=?
                FOR UPDATE
                """,ReturnReceiptEvidenceService::row,tenant,mp,event,claim);
            if(claims.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            Row stored=claims.getFirst();
            RefundRecoveryService.requireIndependent(stored.maker(),actor);
            if(!"PENDING".equals(stored.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"One-shot review already decided");
            var line=item(before,stored.item());
            requireReviewed(before,line,stored.quantity());
            assertSame(tenant,mp,event,stored.item(),before,line);
            if(stored.revision()!=before.revision()
                ||!stored.fingerprint().equals(fingerprint(before,line))
                ||!stored.order().equals(order))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Source quantities or historical cost have changed");
            Integer authors=jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM finance_refund_item_review
                   WHERE tenant_id=? AND event_id=? AND external_item_id=?
                     AND reviewed_by=?)
                  +(SELECT count(*) FROM finance_refund_amount_allocation
                    WHERE tenant_id=? AND event_id=? AND external_item_id=?
                      AND reviewed_by=?)
                """,Integer.class,tenant,event,stored.item(),actor,
                tenant,event,stored.item(),actor);
            if(authors==null||authors!=0)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Receipt reviewer must not author the V34/V35 evidence");
            String decision=approve?"REVIEWED_MANUAL":"REJECTED";
            jdbc.update("""
                UPDATE finance_return_receipt_claim_v96
                SET state=?,decided_by=?,decided_at=now(),decision_reason=?
                WHERE tenant_id=? AND id=? AND state='PENDING'
                """,decision,actor,note,tenant,claim);
            audit(tenant,claim,actor,decision,note);
            return claim;
        });
        return list(tenant,mp,event).stream().filter(x->x.id().equals(result.toString()))
            .findFirst().orElseThrow();
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID event,UUID claim) {
        if(!exists(tenant,mp,event))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Integer valid=jdbc.queryForObject("""
            SELECT count(*) FROM finance_return_receipt_claim_v96
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND id=?
            """,Integer.class,tenant,mp,event,claim);
        if(valid==null||valid!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return jdbc.query("""
            SELECT action,actor_user_id,note,occurred_at
            FROM finance_return_receipt_audit_v96
            WHERE tenant_id=? AND claim_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,n)->new Audit(r.getString(1),r.getObject(2,UUID.class).toString(),
                r.getString(3),r.getObject(4,OffsetDateTime.class).toInstant().toString()),
            tenant,claim);
    }
}
