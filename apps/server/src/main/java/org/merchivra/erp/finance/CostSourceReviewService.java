package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * V163 manual invoice metadata staging and independent local review only.
 * REVIEWED_UNVERIFIED is NOT a validated Ads/Finance charge and never enters
 * V33/V64 or recognized/realized profit. No external HTTP, tokens or file IO.
 */
@Service
public class CostSourceReviewService {
    private final JdbcTemplate jdbc;
    public CostSourceReviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Draft(String category, String sourceKind, String sourceKey,
        String documentSha256, LocalDate businessDate, String amount, String currency) {}
    public record Source(String id,String marketplaceId,String category,
        String sourceKind,String sourceKey,String documentSha256,
        String businessDate,String amount,String currency,String origin,
        String state,String createdBy,String reviewedBy,String reviewedAt) {}
    public record Audit(String action,String actorId,String occurredAt) {}

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Invalid manually supplied cost source");
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Conflicting or previously reviewed cost source");
    }
    private static boolean safeKey(String value) {
        return value != null
            && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}");
    }
    static BigDecimal validate(Draft d) {
        if (d == null || d.category() == null || d.sourceKind() == null
            || d.sourceKey() == null || !safeKey(d.sourceKey())
            || d.businessDate() == null || d.currency() == null
            || !d.currency().matches("[A-Z]{3}")
            || d.documentSha256() == null
            || !d.documentSha256().matches("[0-9a-f]{64}")
            || d.documentSha256().equals("0".repeat(64))
            || !List.of("MANUAL_INVOICE","MANUAL_CARRIER","MANUAL_OTHER")
                .contains(d.sourceKind())
            || !validCategory(d.category())
            || d.amount() == null
            || !d.amount().matches("[0-9]{1,14}(?:[.][0-9]{1,6})?"))
            throw bad();
        BigDecimal amount = new BigDecimal(d.amount());
        if (amount.signum() <= 0)throw bad();
        return amount;
    }
    private static boolean validCategory(String category) {
        try {
            FullCostCoverageEngine.Category.valueOf(category);
            return true;
        } catch(IllegalArgumentException invalid) { return false; }
    }
    static void independent(UUID maker,UUID reviewer,String reason) {
        if (reviewer == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (maker == null || reviewer.equals(maker))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Cost source must be reviewed by a different Owner");
        if (reason == null || reason.length() > 500
            || reason.strip().length() < 10 || reason.indexOf('\n') >= 0
            || reason.indexOf('\r') >= 0)
            throw bad();
    }

    private String marketplaceCurrency(UUID tenant,UUID marketplace) {
        var rows = jdbc.query("""
            SELECT m.currency_code FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id
              AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
            """,(rs,i)->rs.getString(1).trim(),tenant,marketplace);
        if (rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private static Source row(ResultSet r,int i)throws SQLException {
        UUID reviewer=r.getObject("reviewed_by",UUID.class);
        OffsetDateTime when=r.getObject("reviewed_at",OffsetDateTime.class);
        return new Source(r.getObject("id",UUID.class).toString(),
            r.getObject("seller_marketplace_id",UUID.class).toString(),
            r.getString("category"),r.getString("source_kind"),r.getString("source_key"),
            r.getString("source_document_sha256").trim(),
            r.getObject("business_date",LocalDate.class).toString(),
            r.getBigDecimal("amount").toPlainString(),r.getString("currency_code").trim(),
            r.getString("evidence_origin"),r.getString("review_state"),
            r.getObject("created_by",UUID.class).toString(),
            reviewer==null?null:reviewer.toString(),
            when==null?null:when.toInstant().toString());
    }
    private List<Source> findByKey(UUID tenant,UUID mp,String kind,String key) {
        return jdbc.query("""
            SELECT * FROM finance_cost_source_v91
            WHERE tenant_id=? AND seller_marketplace_id=?
              AND source_kind=? AND source_key=?
            """,CostSourceReviewService::row,tenant,mp,kind,key);
    }
    private Source get(UUID tenant,UUID mp,UUID id,boolean lock) {
        var rows=jdbc.query("""
            SELECT * FROM finance_cost_source_v91
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            CostSourceReviewService::row,tenant,mp,id);
        if (rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private void audit(UUID tenant,UUID id,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO finance_cost_source_audit_v91
              (id,tenant_id,source_id,action,actor_id)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,action,actor);
    }
    @Transactional
    public Source create(UUID tenant,UUID mp,UUID actor,Draft draft) {
        if (actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        BigDecimal amount=validate(draft); // bad source blocked before DB access
        String currency=marketplaceCurrency(tenant,mp);
        if(!currency.equals(draft.currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Original currency differs from the Marketplace");
        // Serializes sourceKey replays per tenant+Marketplace, in addition to
        // the unique index. Check all immutable facts on every replay.
        jdbc.queryForList("""
            SELECT id FROM seller_marketplace
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,mp);
        var existing=findByKey(tenant,mp,draft.sourceKind(),draft.sourceKey());
        if(!existing.isEmpty()) {
            Source old=existing.getFirst();
            if(!old.category().equals(draft.category())
                || !old.documentSha256().equals(draft.documentSha256())
                || !old.businessDate().equals(draft.businessDate().toString())
                || !old.currency().equals(draft.currency())
                || new BigDecimal(old.amount()).compareTo(amount)!=0)
                throw conflict();
            return old; // same immutable evidence: safe idempotent replay
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_cost_source_v91
              (id,tenant_id,seller_marketplace_id,category,source_kind,source_key,
               source_document_sha256,business_date,currency_code,amount,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,mp,draft.category(),draft.sourceKind(),draft.sourceKey(),
            draft.documentSha256(),draft.businessDate(),currency,amount,actor);
        audit(tenant,id,actor,"STAGED");
        return get(tenant,mp,id,false);
    }
    public List<Source> list(UUID tenant,UUID mp) {
        marketplaceCurrency(tenant,mp);
        return jdbc.query("""
            SELECT * FROM finance_cost_source_v91
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY business_date DESC,id DESC LIMIT 50
            """,CostSourceReviewService::row,tenant,mp);
    }
    @Transactional
    public Source decide(UUID tenant,UUID mp,UUID id,UUID actor,
                         boolean accept,String reason) {
        if (actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (reason==null || reason.strip().length()<10 || reason.length()>500)
            throw bad();
        marketplaceCurrency(tenant,mp);
        Source current=get(tenant,mp,id,true);
        UUID maker=UUID.fromString(current.createdBy());
        independent(maker,actor,reason);
        if(!current.state().equals("STAGED"))throw conflict();
        String state=accept?"REVIEWED_UNVERIFIED":"REJECTED";
        jdbc.update("""
            UPDATE finance_cost_source_v91
              SET review_state=?,reviewed_by=?,reviewed_at=now(),review_reason=?
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND review_state='STAGED'
            """,state,actor,reason.strip(),tenant,mp,id);
        audit(tenant,id,actor,state);
        return get(tenant,mp,id,false);
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID id) {
        get(tenant,mp,id,false);
        return jdbc.query("""
            SELECT action,actor_id,occurred_at
            FROM finance_cost_source_audit_v91
            WHERE tenant_id=? AND source_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),
                r.getObject(2,UUID.class).toString(),
                r.getObject(3,OffsetDateTime.class).toInstant().toString()),
            tenant,id);
    }
}
