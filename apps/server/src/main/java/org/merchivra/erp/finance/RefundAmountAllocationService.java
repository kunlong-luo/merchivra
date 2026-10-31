package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-reviewed PRODUCT_REFUND portion attribution to specific V34-reviewed items.
 * Each source event owns an independent signed amount; neither refund source,
 * physical inventory, contribution, bank receipts nor financial ledger is changed.
 */
@Service
public class RefundAmountAllocationService {
    private final JdbcTemplate jdbc;
    private final RefundItemReviewService itemReviews;
    private final FinanceClassificationService classification;
    public RefundAmountAllocationService(JdbcTemplate jdbc,
        RefundItemReviewService itemReviews,FinanceClassificationService classification) {
        this.jdbc=jdbc;this.itemReviews=itemReviews;this.classification=classification;
    }
    public record Entry(String itemId,String signedAmount,String currency,
                        String evidenceReference,int revision) {}
    public record Audit(String itemId,String action,String signedAmount,
        String currency,String evidenceReference,int revision,String actorId,String occurredAt) {}
    public record View(String eventId,String orderId,String currency,
        boolean reviewedFinancesRefund,boolean inventoryReceiptVerified,
        RefundAmountAllocationEngine.Summary summary,
        List<Entry> allocatedItems) {}
    private record Evidence(RefundItemReviewService.Snapshot reviewed,
        BigDecimal refundTotal,boolean ready) {}

    private static ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT,text);
    }
    private Evidence evidence(UUID tenant,UUID mp,UUID event) {
        var reviewed=itemReviews.snapshot(tenant,mp,event);
        var p=classification.preview(tenant,mp,event);
        BigDecimal refund=null;
        boolean valid="RECONCILED_UNVERIFIED".equals(p.status())
          &&"MANUAL_REFUND_ITEM_REVIEW".equals(reviewed.status())
          &&p.analysis()!=null;
        if(valid) {
            refund=BigDecimal.ZERO;
            for(var amount:p.analysis().provisionalTotals()) {
                if("PRODUCT_REFUND".equals(amount.category())) {
                    if(!reviewed.currency().equals(amount.currency())) {
                        valid=false;break;
                    }
                    refund=refund.add(new BigDecimal(amount.signedAmount()));
                }
            }
            if(refund.signum()==0)valid=false;
        }
        return new Evidence(reviewed,valid?refund:null,valid);
    }
    private static String safeReference(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Explicit reference (8..160 safe characters) is required");
        return value;
    }
    private static BigDecimal signedAmount(String value) {
        if(value==null||!value.matches("-?[0-9]{1,14}(?:[.][0-9]{1,6})?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Explicit signed original-currency amount with at most 6 decimals required");
        BigDecimal amount=new BigDecimal(value);
        if(amount.signum()==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Zero-valued attribution is not meaningful");
        return amount;
    }
    private List<Entry> allocations(UUID tenant,UUID event) {
        return jdbc.query("""
            SELECT external_item_id,signed_amount,currency_code,reason_reference,revision
            FROM finance_refund_amount_allocation
            WHERE tenant_id=? AND event_id=?
            ORDER BY external_item_id LIMIT 1001
            """,(r,i)->new Entry(r.getString(1),r.getBigDecimal(2).toPlainString(),
                r.getString(3).trim(),r.getString(4),r.getInt(5)),tenant,event);
    }
    public View view(UUID tenant,UUID mp,UUID event) {
        Evidence e=evidence(tenant,mp,event);
        var items=allocations(tenant,event);
        if(items.size()>1000)throw conflict("Too many item refund allocations to preview");
        var amounts=items.stream().map(i->new RefundAmountAllocationEngine.Entry(
            i.itemId(),new BigDecimal(i.signedAmount()),i.currency())).toList();
        var totals=RefundAmountAllocationEngine.evaluate(e.refundTotal(),
            e.reviewed().currency(),e.ready(),amounts);
        return new View(event.toString(),e.reviewed().orderId(),e.reviewed().currency(),
            e.ready(),false,totals,List.copyOf(items));
    }
    /**
     * Lock order first, event second, matching V34 lock order and preventing
     * conflicting V34 withdrawals/finance event rematches during this write.
     */
    private Evidence lockedEvidence(UUID tenant,UUID mp,UUID event) {
        Evidence initial=evidence(tenant,mp,event);
        UUID order=UUID.fromString(initial.reviewed().orderId());
        var orders=jdbc.queryForList("""
            SELECT id FROM sales_order WHERE tenant_id=? AND seller_marketplace_id=?
                AND id=? AND data_origin='LIVE' FOR UPDATE
            """,UUID.class,tenant,mp,order);
        if(orders.size()!=1)throw conflict("Reviewed order changed");
        var source=jdbc.queryForList("""
            SELECT id FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
            FOR UPDATE
            """,UUID.class,tenant,mp,event);
        if(source.size()!=1)throw conflict("Trusted Finances source not found");
        Evidence locked=evidence(tenant,mp,event);
        if(!locked.reviewed().orderId().equals(initial.reviewed().orderId()))
            throw conflict("Reviewed refund order link changed");
        return locked;
    }
    private void audit(UUID tenant,UUID event,UUID actor,Entry item,String action) {
        jdbc.update("""
            INSERT INTO finance_refund_amount_allocation_audit
              (id,tenant_id,event_id,external_item_id,action,signed_amount,
               currency_code,reason_reference,revision,actor_user_id)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,event,item.itemId(),action,
            new BigDecimal(item.signedAmount()),item.currency(),item.evidenceReference(),
            item.revision(),actor);
    }
    @Transactional
    public View save(UUID tenant,UUID mp,UUID event,UUID actor,
        String itemId,String amountText,String currency,String reference) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        BigDecimal amount=signedAmount(amountText);
        safeReference(reference);
        if(itemId==null||itemId.isBlank()||itemId.length()>160)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Order item is required");
        Evidence evidence=lockedEvidence(tenant,mp,event);
        if(!evidence.ready())throw conflict("Fully reviewed PRODUCT_REFUND source required");
        if(!evidence.reviewed().currency().equals(currency))
            throw conflict("Original currency cannot be changed");
        boolean reviewedItem=evidence.reviewed().items().stream()
            .anyMatch(i->itemId.equals(i.itemId())&&i.reviewedQuantity()!=null
                &&i.reviewedQuantity()>0);
        if(!reviewedItem)throw conflict("V34 item quantity review is required");
        var before=allocations(tenant,event);
        if(before.size()>1000)throw conflict("Too many item allocations");
        var prospective=new ArrayList<RefundAmountAllocationEngine.Entry>();
        for(Entry v:before) {
            if(!v.itemId().equals(itemId))prospective.add(
                new RefundAmountAllocationEngine.Entry(v.itemId(),
                    new BigDecimal(v.signedAmount()),v.currency()));
        }
        prospective.add(new RefundAmountAllocationEngine.Entry(itemId,amount,currency));
        try {
            RefundAmountAllocationEngine.requireCandidate(evidence.refundTotal(),
                evidence.reviewed().currency(),prospective);
        } catch(IllegalArgumentException invalid) {
            throw conflict("Signed direction, currency or refund total allocation exceeded");
        }
        Entry previous=before.stream().filter(i->itemId.equals(i.itemId()))
            .findFirst().orElse(null);
        if(previous!=null&&new BigDecimal(previous.signedAmount()).compareTo(amount)==0
            &&reference.equals(previous.evidenceReference()))return view(tenant,mp,event);
        int revision=previous==null?1:previous.revision()+1;
        Entry newEntry=new Entry(itemId,amount.toPlainString(),currency,reference,revision);
        jdbc.update("""
            INSERT INTO finance_refund_amount_allocation
              (tenant_id,event_id,external_item_id,signed_amount,currency_code,
               reason_reference,revision,reviewed_by)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,event_id,external_item_id) DO UPDATE SET
               signed_amount=EXCLUDED.signed_amount,
               currency_code=EXCLUDED.currency_code,
               reason_reference=EXCLUDED.reason_reference,
               revision=EXCLUDED.revision,reviewed_by=EXCLUDED.reviewed_by,
               reviewed_at=now()
            """,tenant,event,itemId,amount,currency,reference,revision,actor);
        audit(tenant,event,actor,newEntry,previous==null?"CREATED":"UPDATED");
        return view(tenant,mp,event);
    }
    @Transactional
    public void withdraw(UUID tenant,UUID mp,UUID event,UUID actor,String itemId) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        lockedEvidence(tenant,mp,event);
        var items=jdbc.query("""
            DELETE FROM finance_refund_amount_allocation
            WHERE tenant_id=? AND event_id=? AND external_item_id=?
            RETURNING external_item_id,signed_amount,currency_code,reason_reference,revision
            """,(r,i)->new Entry(r.getString(1),r.getBigDecimal(2).toPlainString(),
                r.getString(3).trim(),r.getString(4),r.getInt(5)),tenant,event,itemId);
        if(items.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit(tenant,event,actor,items.getFirst(),"WITHDRAWN");
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID event) {
        evidence(tenant,mp,event);
        return jdbc.query("""
            SELECT external_item_id,action,signed_amount,currency_code,
              reason_reference,revision,actor_user_id,occurred_at
            FROM finance_refund_amount_allocation_audit
            WHERE tenant_id=? AND event_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),r.getString(2),
                r.getBigDecimal(3).toPlainString(),r.getString(4).trim(),r.getString(5),
                r.getInt(6),r.getObject(7,UUID.class).toString(),
                r.getObject(8,java.time.OffsetDateTime.class).toInstant().toString()),
            tenant,event);
    }
}
