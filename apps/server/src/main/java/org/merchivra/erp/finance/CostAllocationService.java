package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.finance.CostAllocationEngine.Line;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Immutable manual proposal and independent Owner decision per V91 source.
 * No Finances/Ads external attestation, no ledger or V64 profit writes.
 */
@Service
public class CostAllocationService {
    private final JdbcTemplate jdbc;
    public CostAllocationService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Draft(UUID requestKey,String targetKind,String currency,List<Line> lines) {}
    public record Plan(String id,String sourceId,String requestKey,String targetKind,
        String state,String proposedBy,String reviewedBy,String reviewedAt,
        CostAllocationEngine.Summary summary,List<Line> lines,
        boolean sourceExternallyVerified,boolean recognizedProfitAllowed) {}
    public record Audit(String action,String actorId,String occurredAt) {}
    private record Source(UUID id,UUID marketplace,BigDecimal amount,
        String currency,String state,UUID creator) {}
    private record Batch(UUID id,UUID requestKey,String targetKind,String state,
        UUID proposedBy,UUID reviewedBy,OffsetDateTime reviewedAt) {}

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    static void independent(UUID sourceMaker,UUID proposer,UUID reviewer) {
        if(reviewer==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(sourceMaker==null||proposer==null||reviewer.equals(sourceMaker)
            ||reviewer.equals(proposer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "The source maker and allocation proposer may not approve");
    }
    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }
    private Source source(UUID tenant,UUID marketplace,UUID id,boolean lock) {
        var rows=jdbc.query("""
            SELECT s.id,s.seller_marketplace_id,s.amount,s.currency_code,
                s.review_state,s.created_by
            FROM finance_cost_source_v91 s
            JOIN seller_marketplace m ON m.tenant_id=s.tenant_id
                AND m.id=s.seller_marketplace_id
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            """+(lock?" FOR UPDATE OF s":""),
            (r,i)->new Source(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getBigDecimal(3),r.getString(4).trim(),r.getString(5),
                r.getObject(6,UUID.class)),tenant,marketplace,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private static Batch batchRow(ResultSet r,int i)throws SQLException {
        return new Batch(r.getObject("id",UUID.class),
            r.getObject("request_key",UUID.class),r.getString("target_kind"),
            r.getString("state"),r.getObject("proposed_by",UUID.class),
            r.getObject("reviewed_by",UUID.class),
            r.getObject("reviewed_at",OffsetDateTime.class));
    }
    private Batch batch(UUID tenant,UUID source,UUID id,boolean lock) {
        var rows=jdbc.query("""
            SELECT * FROM finance_cost_allocation_batch_v92
            WHERE tenant_id=? AND source_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            CostAllocationService::batchRow,tenant,source,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private List<Line> lines(UUID tenant,UUID batch) {
        return jdbc.query("""
            SELECT sku_id,order_id,period_after,period_before,expense_amount
            FROM finance_cost_allocation_line_v92
            WHERE tenant_id=? AND batch_id=? ORDER BY line_no LIMIT 101
            """,(r,i)->new Line(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getObject(3,java.time.LocalDate.class),
                r.getObject(4,java.time.LocalDate.class),
                r.getBigDecimal(5).stripTrailingZeros().toPlainString()),
            tenant,batch);
    }
    private Plan view(Source src,Batch b,UUID tenant) {
        var entries=lines(tenant,b.id());
        if(entries.size()>100)throw conflict("Cost allocation line limit exceeded");
        var summary=CostAllocationEngine.evaluate(src.amount().toPlainString(),
            src.currency(),b.targetKind(),entries);
        return new Plan(b.id().toString(),src.id().toString(),
            b.requestKey().toString(),b.targetKind(),b.state(),
            b.proposedBy().toString(),
            b.reviewedBy()==null?null:b.reviewedBy().toString(),
            b.reviewedAt()==null?null:b.reviewedAt().toInstant().toString(),
            summary,List.copyOf(entries),false,false);
    }
    private static List<Line> canonical(Draft d,String sourceAmount,
                                         String sourceCurrency) {
        if(d==null||d.requestKey()==null||d.currency()==null
            ||!d.currency().equals(sourceCurrency))
            throw bad("Original currency and idempotency key are required");
        try {
            CostAllocationEngine.evaluate(sourceAmount,sourceCurrency,
                d.targetKind(),d.lines());
            var target=CostAllocationEngine.Target.valueOf(d.targetKind());
            return d.lines().stream()
                .sorted(Comparator.comparing(line->CostAllocationEngine.key(target,line)))
                .map(line->new Line(line.skuId(),line.orderId(),line.periodAfter(),
                    line.periodBefore(),CostAllocationEngine.positive(line.amount())
                        .stripTrailingZeros().toPlainString()))
                .toList();
        } catch(IllegalArgumentException invalid) {
            throw bad("Invalid or overallocated expense distribution");
        }
    }
    private void targets(UUID tenant,UUID mp,String kind,List<Line> rows) {
        if("PERIOD".equals(kind))return;
        for(Line line:rows) {
            Integer count="SKU".equals(kind)
                ?jdbc.queryForObject("""
                    SELECT count(*) FROM product_sku
                    WHERE tenant_id=? AND id=? AND status='ACTIVE'
                    """,Integer.class,tenant,line.skuId())
                :jdbc.queryForObject("""
                    SELECT count(*) FROM sales_order
                    WHERE tenant_id=? AND id=? AND seller_marketplace_id=?
                    """,Integer.class,tenant,line.orderId(),mp);
            if(count==null||count!=1)
                throw conflict("Cost allocation target unavailable in source scope");
        }
    }
    private void audit(UUID tenant,UUID batch,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO finance_cost_allocation_audit_v92
                (id,tenant_id,batch_id,action,actor_id)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,batch,action,actor);
    }
    @Transactional
    public Plan propose(UUID tenant,UUID mp,UUID sourceId,UUID actor,Draft draft) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(draft==null||draft.requestKey()==null||draft.targetKind()==null
            ||draft.lines()==null||draft.lines().isEmpty()||draft.lines().size()>100)
            throw bad("Missing bounded cost allocation proposal");
        Source src=source(tenant,mp,sourceId,true);
        if(!"REVIEWED_UNVERIFIED".equals(src.state()))
            throw conflict("Independent manual source metadata review is required");
        List<Line> proposed=canonical(draft,src.amount().toPlainString(),src.currency());
        var previous=jdbc.query("""
            SELECT * FROM finance_cost_allocation_batch_v92
            WHERE tenant_id=? AND source_id=? AND request_key=?
            """,CostAllocationService::batchRow,tenant,sourceId,draft.requestKey());
        if(!previous.isEmpty()) {
            Batch old=previous.getFirst();
            if(!old.targetKind().equals(draft.targetKind())
                ||!lines(tenant,old.id()).equals(proposed))
                throw conflict("Idempotency key reused with different allocation facts");
            return view(src,old,tenant);
        }
        Integer active=jdbc.queryForObject("""
            SELECT count(*) FROM finance_cost_allocation_batch_v92
            WHERE tenant_id=? AND source_id=?
                AND state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
            """,Integer.class,tenant,sourceId);
        if(active==null||active!=0)
            throw conflict("Source already has a pending or approved allocation");
        targets(tenant,mp,draft.targetKind(),proposed);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_cost_allocation_batch_v92
                (id,tenant_id,source_id,request_key,target_kind,proposed_by)
            VALUES (?,?,?,?,?,?)
            """,id,tenant,sourceId,draft.requestKey(),draft.targetKind(),actor);
        int n=0;
        for(Line line:proposed) {
            jdbc.update("""
                INSERT INTO finance_cost_allocation_line_v92
                    (tenant_id,batch_id,line_no,sku_id,order_id,period_after,
                     period_before,original_currency,expense_amount)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,tenant,id,++n,line.skuId(),line.orderId(),
                line.periodAfter(),line.periodBefore(),src.currency(),
                CostAllocationEngine.positive(line.amount()));
        }
        audit(tenant,id,actor,"PROPOSED");
        return view(src,batch(tenant,sourceId,id,false),tenant);
    }
    public List<Plan> list(UUID tenant,UUID mp,UUID sourceId) {
        Source src=source(tenant,mp,sourceId,false);
        var batches=jdbc.query("""
            SELECT * FROM finance_cost_allocation_batch_v92
            WHERE tenant_id=? AND source_id=?
            ORDER BY proposed_at DESC,id DESC LIMIT 50
            """,CostAllocationService::batchRow,tenant,sourceId);
        return batches.stream().map(b->view(src,b,tenant)).toList();
    }
    @Transactional
    public Plan decide(UUID tenant,UUID mp,UUID sourceId,UUID batchId,
                       UUID actor,boolean accept,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(reason==null||reason.strip().length()<10||reason.length()>500
            ||reason.indexOf(10)>=0||reason.indexOf(13)>=0)
            throw bad("A single-line 10..500 character reviewer reason is required");
        Source src=source(tenant,mp,sourceId,true);
        Batch current=batch(tenant,sourceId,batchId,true);
        if(!"PENDING_REVIEW".equals(current.state()))
            throw conflict("Cost allocation decision is immutable");
        independent(src.creator(),current.proposedBy(),actor);
        if(!"REVIEWED_UNVERIFIED".equals(src.state()))
            throw conflict("Source review changed");
        var submitted=lines(tenant,batchId);
        if(accept) {
            try {CostAllocationEngine.evaluate(src.amount().toPlainString(),
                src.currency(),current.targetKind(),submitted);}
            catch(IllegalArgumentException invalid) {
                throw conflict("Source allocation does not conserve positive expenses");
            }
        }
        String state=accept?"APPROVED_UNVERIFIED":"REJECTED";
        jdbc.update("""
            UPDATE finance_cost_allocation_batch_v92
            SET state=?,reviewed_by=?,reviewed_at=now(),review_reason=?
            WHERE tenant_id=? AND source_id=? AND id=? AND state='PENDING_REVIEW'
            """,state,actor,reason.strip(),tenant,sourceId,batchId);
        audit(tenant,batchId,actor,state);
        return view(src,batch(tenant,sourceId,batchId,false),tenant);
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID sourceId,UUID batchId) {
        source(tenant,mp,sourceId,false);
        batch(tenant,sourceId,batchId,false);
        return jdbc.query("""
            SELECT action,actor_id,occurred_at
            FROM finance_cost_allocation_audit_v92
            WHERE tenant_id=? AND batch_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),
                r.getObject(2,UUID.class).toString(),
                r.getObject(3,OffsetDateTime.class).toInstant().toString()),
            tenant,batchId);
    }
}
