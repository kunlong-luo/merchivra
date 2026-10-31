package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Immutable financial source facts, separate from provisional Orders and
 * financial settlement/bank receipts. No trusted SP-API writer is exposed by HTTP.
 */
@Service
public class FinanceEventService {
    private final JdbcTemplate jdbc;
    public FinanceEventService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record SourceEvent(String eventKey,String category,String amount,
        String currency,OffsetDateTime postedAt,String externalOrderId,String sourceType) {}
    public record Event(String id,String marketplaceId,String sourceFamily,String eventKey,
        String dataOrigin,String category,String amount,String currency,String postedAt,
        String externalOrderId,String sourceType,String reviewStatus,String reviewedOrderId) {}
    public record Bucket(String dataOrigin,String currency,String category,
        String amount,long eventCount) {}
    public record Review(String eventId,String state,String orderId) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static String category(String v) {
        if(v==null || !List.of("PRODUCT_SALES","PRODUCT_REFUND","AMAZON_FEE",
            "FBA_FEE","ADJUSTMENT","UNCLASSIFIED").contains(v))throw bad("Invalid category");
        return v;
    }
    private static String safeKey(String v) {
        if(v==null || !v.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,199}"))
            throw bad("Source event key must be a stable 8..200 character reference");
        return v;
    }
    private static String sourceType(String v) {
        if(v==null || !v.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}"))
            throw bad("Source type required");
        return v;
    }
    private static String orderRef(String v) {
        if(v==null || v.isBlank())return null;
        if(v.length()>160 || !v.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))
            throw bad("Invalid external order reference");
        return v;
    }
    private static String currency(String v) {
        if(v==null || !v.matches("[A-Z]{3}"))throw bad("Invalid original currency");
        return v;
    }
    private static BigDecimal amount(String v) {
        if(v==null || !v.matches("-?[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw bad("Amount must be signed decimal with up to 6 fractional digits");
        return new BigDecimal(v);
    }
    private static void validate(SourceEvent event) {
        if(event==null)throw bad("Event required");
        safeKey(event.eventKey());
        category(event.category());
        amount(event.amount());
        currency(event.currency());
        sourceType(event.sourceType());
        orderRef(event.externalOrderId());
        if(event.postedAt()==null)throw bad("Posted timestamp required");
    }
    private void requireMarketplace(UUID tenant,UUID marketplace) {
        Integer exists=jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace m
              JOIN seller_account a ON a.tenant_id=m.tenant_id
                AND a.id=m.seller_account_id
              JOIN tenant t ON t.id=m.tenant_id
            WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
            """,Integer.class,tenant,marketplace);
        if(exists==null||exists!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private static Event row(java.sql.ResultSet rs,int i) throws java.sql.SQLException {
        UUID orderId=rs.getObject("reviewed_order_id",UUID.class);
        return new Event(rs.getObject("id",UUID.class).toString(),
            rs.getObject("seller_marketplace_id",UUID.class).toString(),
            rs.getString("source_family"),rs.getString("source_event_key"),
            rs.getString("data_origin"),rs.getString("category"),
            rs.getBigDecimal("amount").toPlainString(),rs.getString("currency_code").trim(),
            rs.getObject("posted_at",OffsetDateTime.class).toInstant().toString(),
            rs.getString("external_order_id"),rs.getString("source_type"),
            rs.getString("review_state"),orderId==null?null:orderId.toString());
    }

    @Transactional
    public Event recordManual(UUID tenant,UUID actor,UUID marketplace,SourceEvent input) {
        if(actor==null)throw bad("Authenticated actor required");
        return persist(tenant,marketplace,input,"MANUAL","MANUAL_UNVERIFIED",actor);
    }

    /**
     * Trusted future Finances worker seam. Caller must independently prove the
     * current app role and credential before acquiring an approved scope.
     * This method never reclassifies a manual event as LIVE.
     */
    @Transactional
    public Event recordTrusted(SpApiSellerScope approved,SourceEvent input) {
        if(approved==null || approved.tenantId()==null || approved.sellerAccountId()==null
          || approved.marketplaceId()==null || approved.marketplaceId().isBlank()
          || !approved.approvedRoles().contains("Finance and Accounting"))
            throw new SecurityException("Finance and Accounting role evidence required");
        List<UUID> allowed=jdbc.query("""
            SELECT m.id FROM seller_marketplace m JOIN seller_account a
              ON a.tenant_id=m.tenant_id AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.seller_account_id=? AND m.marketplace_id=?
              AND a.connection_status='CONNECTED' AND a.seller_id=?
            """,(rs,i)->rs.getObject(1,UUID.class),approved.tenantId(),
            approved.sellerAccountId(),approved.marketplaceId(),approved.sellerId());
        if(allowed.size()!=1)throw new SecurityException("Finances source scope changed");
        return persist(approved.tenantId(),allowed.getFirst(),input,
            "SPAPI_FINANCES","LIVE",null);
    }

    private Event persist(UUID tenant,UUID marketplace,SourceEvent input,
                          String family,String origin,UUID actor) {
        if(tenant==null || marketplace==null)throw bad("Marketplace scope required");
        requireMarketplace(tenant,marketplace);
        validate(input);
        BigDecimal value=amount(input.amount());
        UUID id=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO finance_source_event
              (id,tenant_id,seller_marketplace_id,source_family,source_event_key,
               data_origin,category,amount,currency_code,posted_at,
               external_order_id,source_type,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,seller_marketplace_id,source_family,source_event_key)
              DO NOTHING
            """,id,tenant,marketplace,family,input.eventKey(),origin,
            input.category(),value,input.currency(),input.postedAt(),
            orderRef(input.externalOrderId()),input.sourceType(),actor);
        Event saved=findByKey(tenant,marketplace,family,input.eventKey());
        if(inserted==0 && (
            new BigDecimal(saved.amount()).compareTo(value)!=0
            || !saved.category().equals(input.category())
            || !saved.currency().equals(input.currency())
            || !saved.postedAt().equals(input.postedAt().toInstant().toString())
            || !java.util.Objects.equals(saved.externalOrderId(),orderRef(input.externalOrderId()))
            || !saved.sourceType().equals(input.sourceType())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source event key reused with different facts");
        return saved;
    }

    private Event findByKey(UUID tenant,UUID marketplace,String family,String key) {
        return jdbc.query("""
            SELECT f.*,COALESCE(r.state,'REVIEW_REQUIRED') AS review_state,
              r.order_id AS reviewed_order_id
            FROM finance_source_event f
            LEFT JOIN finance_event_link_review r
              ON r.tenant_id=f.tenant_id AND r.event_id=f.id
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND f.source_family=? AND f.source_event_key=?
            """,FinanceEventService::row,tenant,marketplace,family,key)
            .stream().findFirst().orElseThrow(()->new IllegalStateException(
                "Financial event not persisted"));
    }

    public List<Event> list(UUID tenant,UUID marketplace,int limit,
                            OffsetDateTime beforeAt,UUID beforeId) {
        if(limit<1||limit>100)throw bad("limit must be 1..100");
        if((beforeAt==null)!=(beforeId==null))throw bad("Cursor requires date and ID");
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            SELECT f.*,COALESCE(r.state,'REVIEW_REQUIRED') AS review_state,
              r.order_id AS reviewed_order_id
            FROM finance_source_event f
            LEFT JOIN finance_event_link_review r
              ON r.tenant_id=f.tenant_id AND r.event_id=f.id
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND (CAST(? AS timestamptz) IS NULL OR
                   (f.posted_at,f.id) < (CAST(? AS timestamptz),CAST(? AS uuid)))
            ORDER BY f.posted_at DESC,f.id DESC LIMIT ?
            """,FinanceEventService::row,tenant,marketplace,beforeAt,beforeAt,beforeId,limit);
    }

    public List<Bucket> buckets(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            SELECT data_origin,currency_code,category,
              SUM(amount) AS amount,COUNT(*) AS event_count
            FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=?
            GROUP BY data_origin,currency_code,category
            ORDER BY data_origin,currency_code,category
            """,(rs,i)->new Bucket(rs.getString(1),rs.getString(2).trim(),
                rs.getString(3),rs.getBigDecimal(4).toPlainString(),rs.getLong(5)),
            tenant,marketplace);
    }

    @Transactional
    public Review review(UUID tenant,UUID actor,UUID marketplace,UUID event,UUID order) {
        if(actor==null)throw bad("Authenticated actor required");
        requireMarketplace(tenant,marketplace);
        List<UUID> found=jdbc.query("""
            SELECT id FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getObject(1,UUID.class),tenant,marketplace,event);
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<UUID> reviewedRefundOrders=jdbc.query("""
            SELECT DISTINCT sales_order_id FROM finance_refund_item_review
            WHERE tenant_id=? AND event_id=?
            """,(r,i)->r.getObject(1,UUID.class),tenant,event);
        if(!reviewedRefundOrders.isEmpty()
            &&(order==null||!reviewedRefundOrders.getFirst().equals(order)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Withdraw refund item reviews before changing the finance-order link");
        if(order!=null) {
            Integer valid=jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_order
                WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
                """,Integer.class,tenant,marketplace,order);
            if(valid==null||valid!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String state=order==null?"UNMATCHED":"MATCHED";
        jdbc.update("""
            INSERT INTO finance_event_link_review
              (tenant_id,event_id,order_id,state,reviewed_by,reviewed_at)
            VALUES (?,?,?,?,?,now())
            ON CONFLICT (tenant_id,event_id) DO UPDATE SET
              order_id=EXCLUDED.order_id,state=EXCLUDED.state,
              reviewed_by=EXCLUDED.reviewed_by,reviewed_at=now()
            """,tenant,event,order,state,actor);
        jdbc.update("""
            INSERT INTO finance_event_review_audit
              (id,tenant_id,event_id,actor_user_id,reviewed_order_id,state)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,event,actor,order,state);
        return new Review(event.toString(),state,order==null?null:order.toString());
    }
}
