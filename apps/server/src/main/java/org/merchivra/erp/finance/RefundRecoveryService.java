package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independent review of exactly matched manual V34/V35 refund evidence.
 * Nothing here verifies a physical receipt or posts a cost reversal.
 */
@Service
public class RefundRecoveryService {
    private final JdbcTemplate jdbc;
    private final RefundItemReviewService items;
    private final RefundAmountAllocationService refunds;
    public RefundRecoveryService(JdbcTemplate jdbc,RefundItemReviewService items,
        RefundAmountAllocationService refunds) {
        this.jdbc=jdbc;this.items=items;this.refunds=refunds;
    }
    public record Status(String eventId,String orderId,String state,int revision,
        String evidenceOrigin,String requestedBy,String decidedBy,
        boolean evidenceCurrent,boolean inventoryReceiptVerified,
        boolean accountingPosted,RefundRecoveryEngine.Preview exposure) {}
    public record Audit(String action,int revision,String actorId,
        String reason,String occurredAt) {}
    private record Evidence(UUID orderId,String digest,RefundRecoveryEngine.Preview preview) {}
    private record Stored(String state,int revision,String digest,UUID orderId,
        UUID requestedBy,UUID decidedBy) {}

    static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String reason(String value) {
        if(value==null||value.strip().length()<10||value.length()>500
            ||value.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Reason must be 10..500 printable characters");
        return value.strip();
    }
    static void requireIndependent(UUID maker,UUID reviewer) {
        if(maker==null||reviewer==null||maker.equals(reviewer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A separate Owner must review the refund evidence");
    }
    private Evidence evidence(UUID tenant,UUID mp,UUID event) {
        var source=items.snapshot(tenant,mp,event);
        var amounts=refunds.view(tenant,mp,event);
        if(!source.orderId().equals(amounts.orderId())
            ||!source.currency().equals(amounts.currency())
            ||!"MANUAL_REFUND_ITEM_REVIEW".equals(source.status())
            ||!amounts.reviewedFinancesRefund()
            ||!"COGS_READY_PREVIEW".equals(source.costReadiness())
            ||amounts.summary().sourceRefundAmount()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund, order, source classification or historical costs incomplete");
        var sourceLines=new ArrayList<RefundRecoveryEngine.SourceLine>();
        for(var i:source.items()){
            if(i.reviewedQuantity()==null)continue;
            if(i.orderedQuantity()==null||i.internalSku()==null||i.unitCost()==null
               ||i.revision()==null||i.disposition()==null
               ||i.evidenceReference()==null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Every refunded item requires date-valid original cost");
            sourceLines.add(new RefundRecoveryEngine.SourceLine(i.itemId(),
                i.internalSku(),i.orderedQuantity(),i.reviewedQuantity(),
                i.disposition(),source.currency(),new BigDecimal(i.unitCost()),
                i.revision(),i.evidenceReference()));
        }
        var refundLines=amounts.allocatedItems().stream().map(a->
            new RefundRecoveryEngine.RefundLine(a.itemId(),
                new BigDecimal(a.signedAmount()),a.currency(),
                a.revision(),a.evidenceReference())).toList();
        if(sourceLines.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No item return quantity has been manually reviewed");
        var preview=RefundRecoveryEngine.calculate(source.currency(),
            new BigDecimal(amounts.summary().sourceRefundAmount()),
            sourceLines,refundLines);
        UUID order=UUID.fromString(source.orderId());
        // V262: version the digest and bind the database-assigned revision
        // population, not only reusable application revisions/field values.
        // preview/request/decide already run in a single RR transaction.
        var quantityVersions=jdbc.queryForList("""
            SELECT evidence_revision_v121 FROM finance_refund_item_review
            WHERE tenant_id=? AND event_id=? ORDER BY evidence_revision_v121 LIMIT 101
            """,Long.class,tenant,event);
        var amountVersions=jdbc.queryForList("""
            SELECT evidence_revision_v121 FROM finance_refund_amount_allocation
            WHERE tenant_id=? AND event_id=? ORDER BY evidence_revision_v121 LIMIT 101
            """,Long.class,tenant,event);
        if(quantityVersions.size()!=sourceLines.size()||amountVersions.size()!=refundLines.size()
            ||quantityVersions.size()>100||amountVersions.size()>100
            ||java.util.stream.Stream.concat(quantityVersions.stream(),amountVersions.stream())
                .anyMatch(v->v==null||v<=0)
            ||java.util.stream.Stream.concat(quantityVersions.stream(),amountVersions.stream())
                .distinct().count()!=quantityVersions.size()+amountVersions.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund evidence revision population inconsistent");
        var canonical=new StringBuilder("V121|Q:").append(quantityVersions)
            .append("|A:").append(amountVersions).append('|').append(event).append('|')
            .append(order).append('|').append(source.currency()).append('|')
            .append(preview.signedRefundTotal()).append('|')
            .append(source.refundSourceAmount()).append('|')
            .append(preview.status()).append('|');
        sourceLines.stream()
            .sorted(java.util.Comparator.comparing(RefundRecoveryEngine.SourceLine::itemId))
            .forEach(line->canonical.append(line.itemId()).append(':')
                .append(line.sku()).append(':').append(line.ordered()).append(':')
                .append(line.refunded()).append(':').append(line.disposition()).append(':')
                .append(line.unitCost().toPlainString()).append(':')
                .append(line.reviewRevision()).append(':')
                .append(line.reviewReference()).append('|'));
        refundLines.stream()
            .sorted(java.util.Comparator.comparing(RefundRecoveryEngine.RefundLine::itemId))
            .forEach(line->canonical.append(line.itemId()).append(':')
                .append(line.signedAmount().toPlainString()).append(':')
                .append(line.currency()).append(':').append(line.allocationRevision())
                .append(':').append(line.allocationReference()).append('|'));
        return new Evidence(order,hash(canonical.toString()),preview);
    }
    private Stored stored(UUID tenant,UUID mp,UUID event,boolean lock) {
        var rows=jdbc.query("""
            SELECT state,revision,source_evidence_sha256,sales_order_id,
              requested_by,decided_by
            FROM finance_refund_recovery_review
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
            """+(lock?" FOR UPDATE":""),
            (r,n)->new Stored(r.getString("state"),r.getInt("revision"),
                r.getString("source_evidence_sha256"),
                r.getObject("sales_order_id",UUID.class),
                r.getObject("requested_by",UUID.class),
                r.getObject("decided_by",UUID.class)),tenant,mp,event);
        return rows.stream().findFirst().orElse(null);
    }
    private static Status display(UUID event,Evidence evidence,Stored old) {
        boolean current=old!=null
            &&old.digest().equals(evidence.digest())
            &&old.orderId().equals(evidence.orderId());
        return new Status(event.toString(),evidence.orderId().toString(),
            old==null?"NOT_REQUESTED":current?old.state():"STALE",
            old==null?0:old.revision(),"MANUAL_UNVERIFIED",
            old==null?null:old.requestedBy().toString(),
            old==null||old.decidedBy()==null?null:old.decidedBy().toString(),
            current,false,false,evidence.preview());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Status preview(UUID tenant,UUID mp,UUID event) {
        Evidence src=evidence(tenant,mp,event);
        return display(event,src,stored(tenant,mp,event,false));
    }
    // V264: mirror TenantAccessService's active, tenant-wide Owner predicate.
    // This is a point-in-snapshot authorization check, not an external identity
    // certificate or a complete authorization revision/locking protocol.
    private void requireCurrentOwner(UUID tenant,UUID actor) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM membership m
              JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
              JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
              JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
              WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE'
                AND b.role_code='OWNER' AND b.seller_account_id IS NULL
            )
            """,Boolean.class,tenant,actor)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active tenant Owner required");
    }

    // V261: called within the whole-order RR after preview independently
    // recalculates the V63 evidence digest. Check the exact current revision's
    // request/decision audit pair and current V34/V35 author independence.
    // This is not external identity authentication or a complete ABA fence.
    boolean currentIndependentAudit(UUID tenant,UUID mp,UUID event,Status status) {
        if(status==null||!"REVIEWED".equals(status.state())||!status.evidenceCurrent()
            ||status.revision()<1||status.requestedBy()==null||status.decidedBy()==null
            ||status.requestedBy().equals(status.decidedBy()))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM finance_refund_recovery_review r
              WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.event_id=?
                AND r.sales_order_id=? AND r.state='REVIEWED' AND r.revision=?
                AND r.requested_by=? AND r.decided_by=? AND r.requested_by<>r.decided_by
                AND r.decided_at>=r.requested_at
                AND 2=(SELECT count(DISTINCT m.user_id) FROM membership m
                  JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
                  JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
                  JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
                  WHERE m.tenant_id=r.tenant_id AND m.status='ACTIVE'
                    AND m.user_id IN (r.requested_by,r.decided_by)
                    AND b.role_code='OWNER' AND b.seller_account_id IS NULL)
                AND NOT EXISTS(SELECT 1 FROM local_team_audit a
                  WHERE a.tenant_id=r.tenant_id AND a.event_code='OWNER_REVOKED'
                    AND ((a.subject_user_id=r.requested_by AND a.occurred_at>=r.requested_at)
                      OR (a.subject_user_id=r.decided_by AND a.occurred_at>=r.decided_at)))
                AND NOT EXISTS(SELECT 1 FROM finance_refund_item_review v
                  WHERE v.tenant_id=r.tenant_id AND v.event_id=r.event_id AND v.reviewed_by=r.decided_by)
                AND NOT EXISTS(SELECT 1 FROM finance_refund_amount_allocation v
                  WHERE v.tenant_id=r.tenant_id AND v.event_id=r.event_id AND v.reviewed_by=r.decided_by)
                AND (SELECT count(*) FROM finance_refund_recovery_audit a
                  WHERE a.tenant_id=r.tenant_id AND a.event_id=r.event_id AND a.revision=r.revision)=2
                AND (SELECT count(*) FROM finance_refund_recovery_audit a
                  WHERE a.tenant_id=r.tenant_id AND a.event_id=r.event_id AND a.revision=r.revision
                    AND a.action=CASE WHEN r.revision=1 THEN 'REQUESTED' ELSE 'REOPENED' END
                    AND a.actor_user_id=r.requested_by AND a.source_evidence_sha256=r.source_evidence_sha256
                    AND a.occurred_at=r.requested_at)=1
                AND (SELECT count(*) FROM finance_refund_recovery_audit a
                  WHERE a.tenant_id=r.tenant_id AND a.event_id=r.event_id AND a.revision=r.revision
                    AND a.action='REVIEWED' AND a.actor_user_id=r.decided_by
                    AND a.source_evidence_sha256=r.source_evidence_sha256
                    AND a.occurred_at=r.decided_at)=1
            )
            """,Boolean.class,tenant,mp,event,UUID.fromString(status.orderId()),status.revision(),
                UUID.fromString(status.requestedBy()),UUID.fromString(status.decidedBy())));
    }

    private void lockOrderAndEvent(UUID tenant,UUID mp,UUID event,UUID order) {
        var orderRows=jdbc.queryForList("""
            SELECT id FROM sales_order WHERE tenant_id=? AND seller_marketplace_id=?
              AND id=? AND data_origin='LIVE' FOR UPDATE
            """,UUID.class,tenant,mp,order);
        if(orderRows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "LIVE order no longer available");
        var eventRows=jdbc.queryForList("""
            SELECT id FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
            FOR UPDATE
            """,UUID.class,tenant,mp,event);
        if(eventRows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "LIVE Finances source no longer available");
    }
    private void audit(UUID tenant,UUID event,int revision,UUID actor,
        String action,String digest,String note) {
        jdbc.update("""
            INSERT INTO finance_refund_recovery_audit
              (id,tenant_id,event_id,revision,actor_user_id,action,
               source_evidence_sha256,reason)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,event,revision,actor,action,digest,note);
    }
    static void requireBalanced(RefundRecoveryEngine.Preview exposure) {
        if(!"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(exposure.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Every V34 quantity item needs an exact V35 refund allocation");
    }
    /**
     * A new request may reopen an old final state. We reject an existing PENDING
     * row to prevent one Owner replacing the maker while another is reviewing.
     */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Status request(UUID tenant,UUID mp,UUID event,UUID actor,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        requireCurrentOwner(tenant,actor);
        String note=reason(reason);
        var before=evidence(tenant,mp,event);
        requireBalanced(before.preview());
        lockOrderAndEvent(tenant,mp,event,before.orderId());
        var current=evidence(tenant,mp,event);
        requireBalanced(current.preview());
        if(!current.orderId().equals(before.orderId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund event was reassigned");
        Stored old=stored(tenant,mp,event,true);
        if(old!=null&&"PENDING".equals(old.state())
            &&old.digest().equals(current.digest())
            &&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM finance_refund_recovery_review r
                  JOIN membership m ON m.tenant_id=r.tenant_id AND m.user_id=r.requested_by
                    AND m.status='ACTIVE'
                  JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
                  JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
                    AND b.role_code='OWNER' AND b.seller_account_id IS NULL
                  WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.event_id=?
                    AND NOT EXISTS(SELECT 1 FROM local_team_audit a
                      WHERE a.tenant_id=r.tenant_id AND a.subject_user_id=r.requested_by
                        AND a.event_code='OWNER_REVOKED' AND a.occurred_at>=r.requested_at))
                """,Boolean.class,tenant,mp,event)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Existing review awaits an independent Owner");
        int revision=old==null?1:old.revision()+1;
        if(old==null)jdbc.update("""
            INSERT INTO finance_refund_recovery_review
              (tenant_id,seller_marketplace_id,event_id,sales_order_id,
               state,source_evidence_sha256,requested_by,revision)
            VALUES (?,?,?,?,'PENDING',?,?,?)
            """,tenant,mp,event,current.orderId(),current.digest(),actor,revision);
        else jdbc.update("""
            UPDATE finance_refund_recovery_review
            SET state='PENDING',sales_order_id=?,source_evidence_sha256=?,
              requested_by=?,requested_at=now(),decided_by=NULL,decided_at=NULL,
              revision=?
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
            """,current.orderId(),current.digest(),actor,revision,tenant,mp,event);
        audit(tenant,event,revision,actor,old==null?"REQUESTED":"REOPENED",
            current.digest(),note);
        return display(event,current,stored(tenant,mp,event,false));
    }
    /** Reviewer may not have authored ANY current V34/V35 item review, nor request. */
    private void checkIndependentAuthors(UUID tenant,UUID event,UUID reviewer) {
        Integer edited=jdbc.queryForObject("""
            SELECT
              (SELECT count(*) FROM finance_refund_item_review
               WHERE tenant_id=? AND event_id=? AND reviewed_by=?)
              +(SELECT count(*) FROM finance_refund_amount_allocation
               WHERE tenant_id=? AND event_id=? AND reviewed_by=?)
            """,Integer.class,tenant,event,reviewer,tenant,event,reviewer);
        if(edited==null||edited!=0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Reviewer must differ from current refund item/amount authors");
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Status decide(UUID tenant,UUID mp,UUID event,UUID actor,
        boolean approve,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        requireCurrentOwner(tenant,actor);
        String note=reason(reason);
        var before=evidence(tenant,mp,event);
        lockOrderAndEvent(tenant,mp,event,before.orderId());
        Stored old=stored(tenant,mp,event,true);
        if(old==null||!"PENDING".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending return-cost evidence review");
        requireIndependent(old.requestedBy(),actor);
        requireCurrentOwner(tenant,old.requestedBy());
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM finance_refund_recovery_review r
              JOIN local_team_audit a ON a.tenant_id=r.tenant_id
                AND a.subject_user_id=r.requested_by AND a.event_code='OWNER_REVOKED'
                AND a.occurred_at>=r.requested_at
              WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.event_id=?)
            """,Boolean.class,tenant,mp,event)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Requesting Owner was revoked after the request");
        var current=evidence(tenant,mp,event);
        requireBalanced(current.preview());
        if(!old.orderId().equals(current.orderId())
            ||!old.digest().equals(current.digest()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund quantity, source amount, classification or costs changed");
        checkIndependentAuthors(tenant,event,actor);
        jdbc.update("""
            UPDATE finance_refund_recovery_review SET state=?,decided_by=?,
              decided_at=now() WHERE tenant_id=? AND seller_marketplace_id=?
              AND event_id=? AND state='PENDING' AND revision=?
            """,approve?"REVIEWED":"REJECTED",actor,tenant,mp,event,old.revision());
        audit(tenant,event,old.revision(),actor,approve?"REVIEWED":"REJECTED",
            current.digest(),note);
        return display(event,current,stored(tenant,mp,event,false));
    }
    @Transactional(readOnly=true)
    public List<Audit> history(UUID tenant,UUID mp,UUID event) {
        // Check existing current LIVE refund link before returning historical reasons.
        items.snapshot(tenant,mp,event);
        return jdbc.query("""
            SELECT a.action,a.revision,a.actor_user_id,a.reason,a.occurred_at
            FROM finance_refund_recovery_audit a
            JOIN finance_refund_recovery_review v ON v.tenant_id=a.tenant_id
               AND v.event_id=a.event_id
            WHERE a.tenant_id=? AND v.seller_marketplace_id=? AND a.event_id=?
            ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100
            """,(r,n)->new Audit(r.getString("action"),r.getInt("revision"),
                r.getObject("actor_user_id",UUID.class).toString(),
                r.getString("reason"),
                r.getObject("occurred_at",OffsetDateTime.class).toInstant().toString()),
            tenant,mp,event);
    }
}
