package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manually attested item disposition; NEVER books a refund, restocks inventory
 * or confirms real returned units. A linked LIVE Finances refund is required.
 */
@Service
public class RefundItemReviewService {
    private static final Set<String> DISPOSITIONS=
        Set.of("RESTOCKED_CLAIMED","NOT_RESTOCKED","UNKNOWN");
    private final JdbcTemplate jdbc;
    private final FinanceClassificationService classification;
    private final OrderCostReadinessService costs;
    public RefundItemReviewService(JdbcTemplate jdbc,
        FinanceClassificationService classification,OrderCostReadinessService costs) {
        this.jdbc=jdbc;this.classification=classification;this.costs=costs;
    }
    private record Source(UUID orderId,String externalOrderId,String sourceOrderId,
                          String amount,String currency) {}
    public record Item(String itemId,String sellerSku,Integer orderedQuantity,
        String internalSku,String unitCost,String unitCurrency,
        Integer reviewedQuantity,String disposition,String evidenceReference,
        Integer revision,String restockedCostSensitivity) {}
    public record Snapshot(String eventId,String orderId,String externalOrderId,
        String refundSourceAmount,String currency,String status,
        String costReadiness,boolean inventoryReceiptVerified,
        List<Item> items) {}
    public record Audit(String itemId,String action,int quantity,String disposition,
        String evidenceReference,int revision,String actorId,String timestamp) {}
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private Source source(UUID tenant,UUID mp,UUID event) {
        var rows=jdbc.query("""
            SELECT v.order_id, o.external_order_id, f.external_order_id AS source_order_id,
                   f.amount,f.currency_code
            FROM finance_source_event f
            JOIN finance_event_link_review v ON v.tenant_id=f.tenant_id
              AND v.event_id=f.id AND v.state='MATCHED'
            JOIN sales_order o ON o.tenant_id=f.tenant_id AND o.id=v.order_id
            JOIN seller_marketplace m ON m.tenant_id=f.tenant_id
              AND m.id=f.seller_marketplace_id
            JOIN tenant t ON t.id=f.tenant_id AND t.status='ACTIVE'
            WHERE f.tenant_id=? AND f.seller_marketplace_id=? AND f.id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND o.seller_marketplace_id=f.seller_marketplace_id
              AND o.data_origin='LIVE'
            """,(r,i)->new Source(r.getObject("order_id",UUID.class),
                r.getString("external_order_id"),r.getString("source_order_id"),
                r.getBigDecimal("amount").toPlainString(),
                r.getString("currency_code").trim()),tenant,mp,event);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "LIVE refund must first be reviewed against a LIVE order");
        Source record=rows.getFirst();
        if(record.sourceOrderId()!=null
            && !record.sourceOrderId().equals(record.externalOrderId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Amazon financial Order ID differs from reviewed order");
        return record;
    }
    private void lockOrder(UUID tenant,UUID mp,UUID id) {
        var rows=jdbc.queryForList("""
            SELECT id FROM sales_order WHERE tenant_id=? AND seller_marketplace_id=?
              AND id=? AND data_origin='LIVE' FOR UPDATE
            """,UUID.class,tenant,mp,id);
        if(rows.size()!=1)throw missing();
    }
    private void lockSource(UUID tenant,UUID mp,UUID event) {
        var locked=jdbc.queryForList("""
            SELECT id FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
            FOR UPDATE
            """,UUID.class,tenant,mp,event);
        if(locked.size()!=1)throw missing();
    }
    private void requireRefund(UUID tenant,UUID mp,UUID event) {
        var result=classification.preview(tenant,mp,event);
        if(!"RECONCILED_UNVERIFIED".equals(result.status()) ||
           result.analysis()==null ||
           result.analysis().provisionalTotals().stream()
             .noneMatch(v->"PRODUCT_REFUND".equals(v.category())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source must have fully reconciled owner-reviewed refund classification");
    }
    private static String validEvidence(String evidence) {
        if(evidence==null ||
          !evidence.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Local evidence reference must be 8..160 safe characters");
        return evidence;
    }
    private static String validDisposition(String disposition) {
        if(!DISPOSITIONS.contains(disposition))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid returned item disposition");
        return disposition;
    }
    private void audit(UUID tenant,UUID event,UUID order,String item,UUID actor,
        String action,int quantity,String disposition,String evidence,int revision) {
        jdbc.update("""
            INSERT INTO finance_refund_review_audit
              (id,tenant_id,event_id,sales_order_id,external_item_id,actor_user_id,
               action,reviewed_quantity,disposition,evidence_reference,revision)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,event,order,item,actor,action,quantity,
            disposition,evidence,revision);
    }
    /**
     * A review can support a future inventory inquiry. Even RESTOCKED_CLAIMED
     * is not a trusted stock receipt; preview stays unverified.
     */
    public Snapshot snapshot(UUID tenant,UUID mp,UUID event) {
        Source src=source(tenant,mp,event);
        var classificationResult=classification.preview(tenant,mp,event);
        boolean refund="RECONCILED_UNVERIFIED".equals(classificationResult.status())
            &&classificationResult.analysis()!=null
            &&classificationResult.analysis().provisionalTotals().stream()
               .anyMatch(v->"PRODUCT_REFUND".equals(v.category()));
        var cogs=costs.preview(tenant,mp,src.orderId());
        var reviews=jdbc.query("""
            SELECT external_item_id,reviewed_quantity,disposition,evidence_reference,revision
            FROM finance_refund_item_review
            WHERE tenant_id=? AND event_id=? AND sales_order_id=?
            """,(r,i)->new Object[]{
                r.getString("external_item_id"),r.getInt("reviewed_quantity"),
                r.getString("disposition"),r.getString("evidence_reference"),
                r.getInt("revision")},tenant,event,src.orderId());
        var items=new java.util.ArrayList<Item>();
        for(var line:cogs.items()) {
            Object[] review=reviews.stream()
                .filter(row->Objects.equals(row[0],line.itemId()))
                .findFirst().orElse(null);
            Integer qty=review==null?null:(Integer)review[1];
            String disposition=review==null?null:(String)review[2];
            String evidence=review==null?null:(String)review[3];
            Integer version=review==null?null:(Integer)review[4];
            String sensitivity=null;
            if("COGS_READY_PREVIEW".equals(cogs.readiness())&&
                "READY".equals(line.issue())&&qty!=null&&
                "RESTOCKED_CLAIMED".equals(disposition)) {
                sensitivity=RefundReviewEvidenceEngine.unverifiedRestockSensitivity(
                    disposition,new BigDecimal(line.unitCost()),qty,
                    cogs.currency(),line.currency());
            }
            items.add(new Item(line.itemId(),line.sellerSku(),line.quantity(),
                line.internalSku(),line.unitCost(),line.currency(),qty,
                disposition,evidence,version,sensitivity));
        }
        return new Snapshot(event.toString(),src.orderId().toString(),
            src.externalOrderId(),src.amount(),src.currency(),
            refund?"MANUAL_REFUND_ITEM_REVIEW":"REFUND_CLASSIFICATION_INCOMPLETE",
            cogs.readiness(),false,List.copyOf(items));
    }
    @Transactional
    public Snapshot save(UUID tenant,UUID mp,UUID actor,UUID event,
        String itemId,int quantity,String disposition,String evidence) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(itemId==null||itemId.isBlank()||itemId.length()>160||quantity<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Item reference and positive refund quantity required");
        validDisposition(disposition);validEvidence(evidence);
        Source src=source(tenant,mp,event);
        lockOrder(tenant,mp,src.orderId());
        // Serialize with FinanceEventService.review, which locks this event row.
        lockSource(tenant,mp,event);
        // A revoked/reassigned link must be rechecked after the order lock.
        Source locked=source(tenant,mp,event);
        if(!src.orderId().equals(locked.orderId()))throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Order link changed while reviewing refund");
        requireRefund(tenant,mp,event);
        var valid=jdbc.query("""
            SELECT quantity FROM order_item
            WHERE tenant_id=? AND sales_order_id=? AND external_item_id=?
              AND data_origin='LIVE' AND is_current=true
            """,(r,i)->r.getObject(1,Integer.class),tenant,src.orderId(),itemId);
        if(valid.size()!=1||valid.getFirst()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A current LIVE item with known ordered quantity is required");
        int ordered=valid.getFirst();

        var older=jdbc.query("""
            SELECT reviewed_quantity,disposition,evidence_reference,revision
            FROM finance_refund_item_review
            WHERE tenant_id=? AND event_id=? AND external_item_id=?
            """,(r,i)->new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getInt(4)},
            tenant,event,itemId);
        int oldQty=older.isEmpty()?0:(Integer)older.getFirst()[0];
        Integer allocated=jdbc.queryForObject("""
            SELECT COALESCE(SUM(reviewed_quantity),0)::integer
            FROM finance_refund_item_review
            WHERE tenant_id=? AND sales_order_id=? AND external_item_id=? AND event_id<>?
            """,Integer.class,tenant,src.orderId(),itemId,event);
        try {
            RefundReviewEvidenceEngine.requireQuantity(
                ordered,quantity,allocated==null?-1:allocated);
        } catch(IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund reviews across source transactions exceed ordered units");
        }
        if(!older.isEmpty()
            &&oldQty==quantity
            &&disposition.equals(older.getFirst()[1])
            &&evidence.equals(older.getFirst()[2]))return snapshot(tenant,mp,event);
        int revision=older.isEmpty()?1:((Integer)older.getFirst()[3])+1;
        jdbc.update("""
            INSERT INTO finance_refund_item_review
              (tenant_id,event_id,sales_order_id,external_item_id,reviewed_quantity,
               disposition,evidence_reference,revision,reviewed_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,event_id,external_item_id)
            DO UPDATE SET reviewed_quantity=EXCLUDED.reviewed_quantity,
              disposition=EXCLUDED.disposition,
              evidence_reference=EXCLUDED.evidence_reference,
              revision=EXCLUDED.revision,reviewed_by=EXCLUDED.reviewed_by,
              reviewed_at=now()
            """,tenant,event,src.orderId(),itemId,quantity,disposition,evidence,
            revision,actor);
        audit(tenant,event,src.orderId(),itemId,actor,
            older.isEmpty()?"CREATED":"UPDATED",quantity,disposition,evidence,revision);
        return snapshot(tenant,mp,event);
    }
    @Transactional
    public void withdraw(UUID tenant,UUID mp,UUID actor,UUID event,String itemId) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Source src=source(tenant,mp,event);
        lockOrder(tenant,mp,src.orderId());
        lockSource(tenant,mp,event);
        if(!src.orderId().equals(source(tenant,mp,event).orderId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Order link changed");
        Integer attributed=jdbc.queryForObject("""
            SELECT count(*) FROM finance_refund_amount_allocation
            WHERE tenant_id=? AND event_id=? AND external_item_id=?
            """,Integer.class,tenant,event,itemId);
        if(attributed!=null&&attributed>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Withdraw V35 item amount attribution before removing V34 quantity review");
        var rows=jdbc.query("""
            DELETE FROM finance_refund_item_review
            WHERE tenant_id=? AND event_id=? AND sales_order_id=?
              AND external_item_id=?
            RETURNING reviewed_quantity,disposition,evidence_reference,revision
            """,(r,i)->new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getInt(4)},
            tenant,event,src.orderId(),itemId);
        if(rows.size()!=1)throw missing();
        Object[] r=rows.getFirst();
        audit(tenant,event,src.orderId(),itemId,actor,"WITHDRAWN",
            (Integer)r[0],(String)r[1],(String)r[2],(Integer)r[3]);
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID event) {
        source(tenant,mp,event);
        return jdbc.query("""
            SELECT external_item_id,action,reviewed_quantity,disposition,
              evidence_reference,revision,actor_user_id,occurred_at
            FROM finance_refund_review_audit
            WHERE tenant_id=? AND event_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),r.getString(2),r.getInt(3),
                r.getString(4),r.getString(5),r.getInt(6),
                r.getObject(7,UUID.class).toString(),
                r.getObject(8,java.time.OffsetDateTime.class).toInstant().toString()),
            tenant,event);
    }
}
