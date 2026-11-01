package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual, independently approved per-order-item allocation of already linked,
 * fully classified LIVE Finances. No remote write, bank payout or ledger posting.
 */
@Service
public class ItemAllocationService {
    private final JdbcTemplate jdbc;
    private final FinanceClassificationService classification;
    private final OrderCostReadinessService costs;
    public ItemAllocationService(JdbcTemplate jdbc,FinanceClassificationService classification,
        OrderCostReadinessService costs) {
        this.jdbc=jdbc;this.classification=classification;this.costs=costs;
    }
    public record Item(String externalItemId,String sku,String cost,String currency,
        int quantity) {}
    public record Allocation(String externalItemId,String category,String signedAmount,
        String currency,String evidenceReference) {}
    public record View(String eventId,String orderId,String state,int version,
        String currency,String requestedBy,String approvedBy,boolean evidenceCurrent,
        boolean financialSourceComplete,boolean bankVerified,
        ItemAllocationEngine.Summary amounts,List<Item> items,
        List<Allocation> lines) {}
    public record Audit(int version,String action,String itemId,String category,
        String signedAmount,String actor,String reason,String occurredAt) {}
    private record Source(UUID orderId,String currency,String digest,
        Map<String,BigDecimal> categories,List<Item> items) {}
    private record Stored(String state,String digest,UUID orderId,UUID requester,
        UUID approver,int version) {}

    static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String reason(String text) {
        if(text==null||text.length()>500||text.strip().length()<10
            ||text.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Review reason must be 10..500 printable characters");
        return text.strip();
    }
    private static BigDecimal amount(String text) {
        if(text==null||!text.matches("-?[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Signed amount must have at most six fractional places");
        BigDecimal value=new BigDecimal(text);
        if(value.signum()==0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Zero amounts cannot be assigned to an order item");
        return value;
    }
    static String reference(String text) {
        if(text==null||!text.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An anonymized evidence reference of 8..160 characters is required");
        return text;
    }

    /**
     * Only an explicit reviewed event->order link is accepted. Cost & mapping
     * are reconstructed from immutable original order date every operation.
     */
    private Source source(UUID tenant,UUID mp,UUID event) {
        var orders=jdbc.query("""
            SELECT l.order_id
            FROM finance_event_link_review l
            JOIN finance_source_event f ON f.tenant_id=l.tenant_id
              AND f.id=l.event_id
            JOIN sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id
            JOIN tenant t ON t.id=l.tenant_id
            WHERE l.tenant_id=? AND l.event_id=? AND l.state='MATCHED'
              AND f.seller_marketplace_id=? AND o.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND o.data_origin='LIVE' AND t.status='ACTIVE'
            """,(r,n)->r.getObject("order_id",UUID.class),tenant,event,mp,mp);
        if(orders.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Event must be explicitly linked to one LIVE order in this Marketplace");
        UUID orderId=orders.getFirst();
        var cost=costs.preview(tenant,mp,orderId);
        if(!"COGS_READY_PREVIEW".equals(cost.readiness())
           ||cost.items().size()<2||cost.items().size()>100)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Bounded order-item cost and mapping review required");
        var itemIds=new java.util.HashSet<String>();
        var skuNames=new java.util.HashSet<String>();
        List<Item> items=new ArrayList<>();
        for(var line:cost.items()) {
            if(!"READY".equals(line.issue())||line.extendedCost()==null
               ||line.quantity()==null||line.quantity()<=0
               ||line.internalSku()==null||!itemIds.add(line.itemId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Every current order item must have unique mapping, units and cost");
            skuNames.add(line.internalSku());
            items.add(new Item(line.itemId(),line.internalSku(),
                line.extendedCost(),line.currency(),line.quantity()));
        }
        if(skuNames.size()<2)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only orders with two or more distinct internal SKUs need this allocation");
        var eventPreview=classification.preview(tenant,mp,event);
        if(!"RECONCILED_UNVERIFIED".equals(eventPreview.status())
           ||eventPreview.analysis()==null
           ||!cost.currency().equals(eventPreview.analysis().currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Full LIVE Finances classification and original currency required");
        Map<String,BigDecimal> categories=new TreeMap<>();
        for(var c:eventPreview.analysis().provisionalTotals()) {
            if(!ItemAllocationEngine.ELIGIBLE.contains(c.category()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Refunds, adjustments and unknown fees must be handled separately");
            if(!cost.currency().equals(c.currency()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Mixed currencies cannot be allocated");
            BigDecimal value=new BigDecimal(c.signedAmount());
            if(value.signum()!=0)categories.merge(c.category(),value,BigDecimal::add);
        }
        if(categories.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No nonzero eligible classified source components");
        // Explicit canonical fields, deterministic order, no URL/PII/secrets.
        var canonical=new StringBuilder(orderId.toString()).append('|')
            .append(event).append('|').append(cost.currency()).append('|');
        for(var e:categories.entrySet())
            canonical.append(e.getKey()).append(':').append(e.getValue().toPlainString()).append('|');
        items.stream().sorted(java.util.Comparator.comparing(Item::externalItemId))
            .forEach(i->canonical.append(i.externalItemId()).append(':')
                .append(i.sku()).append(':').append(i.quantity()).append(':')
                .append(i.cost()).append(':').append(i.currency()).append('|'));
        return new Source(orderId,cost.currency(),hash(canonical.toString()),
            Map.copyOf(categories),List.copyOf(items));
    }
    private void lockEvent(UUID tenant,UUID mp,UUID event) {
        var ids=jdbc.queryForList("""
            SELECT id FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            FOR UPDATE
            """,UUID.class,tenant,mp,event);
        if(ids.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private Stored stored(UUID tenant,UUID event,boolean lock) {
        return jdbc.query("""
            SELECT state,evidence_sha256,order_id,requested_by,approved_by,version
            FROM finance_item_allocation_review WHERE tenant_id=? AND event_id=?
            """+(lock?" FOR UPDATE":""),
            (r,n)->new Stored(r.getString("state"),r.getString("evidence_sha256"),
                r.getObject("order_id",UUID.class),
                r.getObject("requested_by",UUID.class),
                r.getObject("approved_by",UUID.class),r.getInt("version")),
            tenant,event).stream().findFirst().orElse(null);
    }
    private List<Allocation> lines(UUID tenant,UUID event) {
        return jdbc.query("""
            SELECT external_item_id,category,signed_amount,currency_code,
              evidence_reference
            FROM finance_item_allocation_line
            WHERE tenant_id=? AND event_id=?
            ORDER BY category,external_item_id LIMIT 201
            """,(r,n)->new Allocation(r.getString(1),r.getString(2),
                r.getBigDecimal(3).toPlainString(),r.getString(4).trim(),
                r.getString(5)),tenant,event);
    }
    private static ItemAllocationEngine.Summary summarize(Source src,
        List<Allocation> lines) {
        var itemIds=src.items().stream().map(Item::externalItemId)
            .collect(java.util.stream.Collectors.toSet());
        return ItemAllocationEngine.summarize(src.currency(),src.categories(),
            lines.stream().map(l->new ItemAllocationEngine.Line(l.externalItemId(),
                l.category(),new BigDecimal(l.signedAmount()),
                l.currency(),l.evidenceReference())).toList(),itemIds);
    }
    private static View display(UUID event,Source src,Stored stored,
        List<Allocation> lines) {
        boolean current=stored!=null&&stored.digest().equals(src.digest())
            &&stored.orderId().equals(src.orderId());
        ItemAllocationEngine.Summary amounts;
        try {amounts=summarize(src,lines);}
        catch(IllegalArgumentException stale) {
            amounts=ItemAllocationEngine.summarize(src.currency(),
                src.categories(),List.of(),
                src.items().stream().map(Item::externalItemId)
                    .collect(java.util.stream.Collectors.toSet()));
            current=false;
        }
        return new View(event.toString(),src.orderId().toString(),
            stored==null?"NOT_STARTED":!current?"STALE":stored.state(),
            stored==null?0:stored.version(),src.currency(),
            stored==null?null:stored.requester().toString(),
            stored==null||stored.approver()==null?null:stored.approver().toString(),
            current,false,false,amounts,src.items(),List.copyOf(lines));
    }
    @Transactional(readOnly=true)
    public View preview(UUID tenant,UUID mp,UUID event) {
        Source src=source(tenant,mp,event);
        return display(event,src,stored(tenant,event,false),lines(tenant,event));
    }
    private void audit(UUID tenant,UUID event,int version,String action,
        String item,String category,BigDecimal amount,String digest,
        UUID actor,String note) {
        jdbc.update("""
            INSERT INTO finance_item_allocation_audit
             (id,tenant_id,event_id,version,actor_user_id,action,
              item_id,category,signed_amount,reason,evidence_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,event,version,actor,action,
            item,category,amount,note,digest);
    }
    private Stored draft(UUID tenant,UUID mp,UUID event,UUID actor,
        Source src,Stored before,String note) {
        if(before==null) {
            jdbc.update("""
                INSERT INTO finance_item_allocation_review
                 (tenant_id,seller_marketplace_id,event_id,order_id,
                  state,evidence_sha256,requested_by)
                VALUES (?,?,?,?,'DRAFT',?,?)
                """,tenant,mp,event,src.orderId(),src.digest(),actor);
            audit(tenant,event,1,"DRAFT_CREATED",null,null,null,src.digest(),actor,note);
        }else{
            if(!"DRAFT".equals(before.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Review must be reopened before editing a pending or decided allocation");
            if(!actor.equals(before.requester()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the drafting Owner may change allocated amounts");
            if(!src.digest().equals(before.digest())
                ||!src.orderId().equals(before.orderId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Source evidence changed; reopen and discard old item allocations");
        }
        return stored(tenant,event,true);
    }
    /** An item/category may be updated or withdrawn only by the original drafter. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View save(UUID tenant,UUID mp,UUID event,UUID actor,
        String item,String category,String signedAmount,String currency,
        String evidenceReference,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=reason(reason);
        BigDecimal value=signedAmount==null?null:amount(signedAmount);
        if(!ItemAllocationEngine.ELIGIBLE.contains(category)
            ||item==null||item.isBlank()||item.length()>160)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact eligible item and category required");
        if(value!=null)reference(evidenceReference);
        lockEvent(tenant,mp,event);
        Source src=source(tenant,mp,event);
        if(!src.currency().equals(currency))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Original currency cannot be changed");
        if(src.items().stream().noneMatch(i->i.externalItemId().equals(item)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Item not in reviewed order");
        Stored before=stored(tenant,event,true);
        Stored active=draft(tenant,mp,event,actor,src,before,note);
        var candidate=new ArrayList<>(lines(tenant,event));
        candidate.removeIf(i->i.externalItemId().equals(item)&&i.category().equals(category));
        if(value!=null)candidate.add(new Allocation(item,category,value.toPlainString(),
            currency,evidenceReference));
        try{summarize(src,candidate);}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Incorrect sign, category, source total or duplicated item allocation");}
        if(value==null)jdbc.update("""
            DELETE FROM finance_item_allocation_line
            WHERE tenant_id=? AND event_id=? AND external_item_id=? AND category=?
            """,tenant,event,item,category);
        else jdbc.update("""
            INSERT INTO finance_item_allocation_line
             (tenant_id,event_id,order_id,external_item_id,category,
              signed_amount,currency_code,evidence_reference)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_id,event_id,external_item_id,category)
            DO UPDATE SET signed_amount=EXCLUDED.signed_amount,
              evidence_reference=EXCLUDED.evidence_reference
            """,tenant,event,src.orderId(),item,category,value,currency,evidenceReference);
        int version=active.version()+1;
        jdbc.update("""
            UPDATE finance_item_allocation_review SET version=?,requested_at=now()
            WHERE tenant_id=? AND event_id=?
            """,version,tenant,event);
        audit(tenant,event,version,value==null?"LINE_WITHDRAWN":"LINE_SAVED",
            item,category,value,src.digest(),actor,note);
        return display(event,src,stored(tenant,event,false),lines(tenant,event));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View submit(UUID tenant,UUID mp,UUID event,UUID actor,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=reason(reason);
        lockEvent(tenant,mp,event);
        Source src=source(tenant,mp,event);
        Stored old=stored(tenant,event,true);
        if(old==null||!"DRAFT".equals(old.state())
            ||!old.requester().equals(actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only drafting Owner may submit an open draft");
        View current=display(event,src,old,lines(tenant,event));
        if(!current.evidenceCurrent()||!current.amounts().fullyAllocated())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "All eligible signed category totals must be allocated exactly");
        jdbc.update("""
            UPDATE finance_item_allocation_review SET state='PENDING',
              requested_at=now() WHERE tenant_id=? AND event_id=?
            """,tenant,event);
        audit(tenant,event,old.version(),"SUBMITTED",null,null,null,
            src.digest(),actor,note);
        return display(event,src,stored(tenant,event,false),lines(tenant,event));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View decide(UUID tenant,UUID mp,UUID event,UUID actor,
        boolean approve,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=reason(reason);
        lockEvent(tenant,mp,event);
        Stored old=stored(tenant,event,true);
        if(old==null||!"PENDING".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending independent item allocation");
        requireIndependent(old.requester(),actor);
        Source src=source(tenant,mp,event);
        View current=display(event,src,old,lines(tenant,event));
        if(!current.evidenceCurrent()||!current.amounts().fullyAllocated())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Allocation source or category totals changed; review is stale");
        jdbc.update("""
            UPDATE finance_item_allocation_review SET state=?,
              approved_by=?,approved_at=now()
            WHERE tenant_id=? AND event_id=? AND state='PENDING'
            """,approve?"APPROVED":"REJECTED",actor,tenant,event);
        audit(tenant,event,old.version(),approve?"APPROVED":"REJECTED",
            null,null,null,src.digest(),actor,note);
        return display(event,src,stored(tenant,event,false),lines(tenant,event));
    }
    static void requireIndependent(UUID maker,UUID reviewer) {
        if(maker==null||reviewer==null||maker.equals(reviewer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "The drafting Owner cannot review their own allocation");
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View reopen(UUID tenant,UUID mp,UUID event,UUID actor,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=reason(reason);
        lockEvent(tenant,mp,event);
        Source src=source(tenant,mp,event);
        Stored old=stored(tenant,event,true);
        if(old==null||"DRAFT".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only submitted/decided allocations can be reopened");
        // Source changes make all existing item shares stale. Require re-entry.
        jdbc.update("""
            DELETE FROM finance_item_allocation_line
            WHERE tenant_id=? AND event_id=?
            """,tenant,event);
        jdbc.update("""
            UPDATE finance_item_allocation_review SET
              state='DRAFT',evidence_sha256=?,order_id=?,version=?,
              requested_by=?,requested_at=now(),approved_by=NULL,approved_at=NULL
            WHERE tenant_id=? AND event_id=? AND seller_marketplace_id=?
            """,src.digest(),src.orderId(),old.version()+1,actor,tenant,event,mp);
        audit(tenant,event,old.version()+1,"REOPENED",null,null,null,
            src.digest(),actor,note);
        return display(event,src,stored(tenant,event,false),lines(tenant,event));
    }
    @Transactional(readOnly=true)
    public List<Audit> history(UUID tenant,UUID mp,UUID event) {
        source(tenant,mp,event);
        return jdbc.query("""
            SELECT a.version,a.action,a.item_id,a.category,a.signed_amount,
              a.actor_user_id,a.reason,a.occurred_at
            FROM finance_item_allocation_audit a
            JOIN finance_item_allocation_review v ON v.tenant_id=a.tenant_id
              AND v.event_id=a.event_id
            WHERE a.tenant_id=? AND v.seller_marketplace_id=? AND a.event_id=?
            ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100
            """,(r,n)->new Audit(r.getInt("version"),r.getString("action"),
                r.getString("item_id"),r.getString("category"),
                r.getBigDecimal("signed_amount")==null?null:
                    r.getBigDecimal("signed_amount").toPlainString(),
                r.getObject("actor_user_id",UUID.class).toString(),
                r.getString("reason"),r.getObject("occurred_at",
                    OffsetDateTime.class).toInstant().toString()),tenant,mp,event);
    }
    @Transactional(readOnly=true)
    public List<UUID> linkedEvents(UUID tenant,UUID mp,UUID order) {
        return jdbc.query("""
            SELECT f.id FROM finance_event_link_review l
            JOIN finance_source_event f ON f.tenant_id=l.tenant_id AND f.id=l.event_id
            WHERE l.tenant_id=? AND l.order_id=? AND l.state='MATCHED'
              AND f.seller_marketplace_id=? AND f.source_family='SPAPI_FINANCES'
              AND f.data_origin='LIVE'
            ORDER BY f.id LIMIT 101
            """,(r,n)->r.getObject(1,UUID.class),tenant,order,mp);
    }
}
