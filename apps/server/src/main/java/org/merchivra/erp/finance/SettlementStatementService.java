package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local manually quoted statement-to-source matching; no bank access or
 * authenticated settlement confirmation. Original Finances events remain immutable.
 */
@Service
public class SettlementStatementService {
    private final JdbcTemplate jdbc;
    public SettlementStatementService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Statement(String id,String reference,String periodStart,
        String periodEnd,String reportedNet,String currency,String evidenceOrigin,
        String createdAt) {}
    public record Candidate(String id,String sourceKey,String sourceType,
        String amount,String currency,String postedAt) {}
    public record Audit(String action,String eventId,String actorId,String occurredAt) {}

    private static Instant at(java.sql.ResultSet r,String name)
        throws java.sql.SQLException {
        return r.getObject(name,OffsetDateTime.class).toInstant();
    }
    private static String time(java.sql.ResultSet r,String name)
        throws java.sql.SQLException { return at(r,name).toString(); }
    private static OffsetDateTime db(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
    private static Statement statement(java.sql.ResultSet r,int i)
        throws java.sql.SQLException {
        return new Statement(r.getObject("id",UUID.class).toString(),
            r.getString("statement_ref"),time(r,"period_start"),time(r,"period_end"),
            r.getBigDecimal("reported_net").toPlainString(),
            r.getString("currency_code").trim(),r.getString("evidence_origin"),
            time(r,"created_at"));
    }
    private static Candidate candidate(java.sql.ResultSet r,int i)
        throws java.sql.SQLException {
        return new Candidate(r.getObject("id",UUID.class).toString(),
            r.getString("source_event_key"),r.getString("source_type"),
            r.getBigDecimal("amount").toPlainString(),r.getString("currency_code").trim(),
            time(r,"posted_at"));
    }
    private void requireMarketplace(UUID tenant,UUID mp) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM seller_marketplace m JOIN tenant t ON t.id=m.tenant_id
            WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
            """,Integer.class,tenant,mp);
        if(count==null||count!=1)throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Marketplace not available");
    }
    private Statement get(UUID tenant,UUID mp,UUID id,boolean lock) {
        List<Statement> found=jdbc.query("""
            SELECT * FROM finance_settlement_statement
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            SettlementStatementService::statement,tenant,mp,id);
        if(found.isEmpty())throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Statement not found");
        return found.getFirst();
    }
    private void audit(UUID tenant,UUID statement,UUID event,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO finance_settlement_audit
              (id,tenant_id,statement_id,event_id,action,actor_user_id)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,statement,event,action,actor);
    }
    public List<Statement> list(UUID tenant,UUID mp) {
        requireMarketplace(tenant,mp);
        return jdbc.query("""
            SELECT * FROM finance_settlement_statement
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY created_at DESC,id DESC LIMIT 50
            """,SettlementStatementService::statement,tenant,mp);
    }
    @Transactional
    public Statement create(UUID tenant,UUID mp,UUID actor,String reference,
        OffsetDateTime from,OffsetDateTime until,String amount,String currency) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(reference==null ||
           !reference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}") ||
           from==null||until==null ||
           !from.toInstant().isBefore(until.toInstant()) ||
           Duration.between(from.toInstant(),until.toInstant()).compareTo(
               Duration.ofDays(92))>0 ||
           amount==null||!amount.matches("-?[0-9]{1,14}(?:[.][0-9]{1,6})?") ||
           currency==null||!currency.matches("[A-Z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Statement needs a bounded period, original currency and signed net");
        requireMarketplace(tenant,mp);
        List<String> supported=jdbc.query("""
            SELECT currency_code FROM seller_marketplace WHERE tenant_id=? AND id=?
            FOR SHARE
            """,(r,i)->r.getString(1).trim(),tenant,mp);
        if(supported.size()!=1||!currency.equals(supported.getFirst()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cross-currency settlement statements require explicit FX review");
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_settlement_statement
                  (id,tenant_id,seller_marketplace_id,statement_ref,
                   period_start,period_end,reported_net,currency_code,created_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,id,tenant,mp,reference,from,until,new BigDecimal(amount),currency,actor);
        } catch(DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Statement reference already exists in selected Marketplace");
        }
        audit(tenant,id,null,actor,"CREATED");
        return get(tenant,mp,id,false);
    }
    public List<Candidate> candidates(UUID tenant,UUID mp,UUID statementId) {
        Statement s=get(tenant,mp,statementId,false);
        return jdbc.query("""
            SELECT f.id,f.source_event_key,f.source_type,f.amount,f.currency_code,f.posted_at
            FROM finance_source_event f
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND f.currency_code=?
              AND f.posted_at>=? AND f.posted_at<?
              AND NOT EXISTS (SELECT 1 FROM finance_settlement_event_link l
                WHERE l.tenant_id=f.tenant_id AND l.event_id=f.id)
            ORDER BY f.posted_at DESC,f.id DESC LIMIT 100
            """,SettlementStatementService::candidate,
            tenant,mp,s.currency(),OffsetDateTime.parse(s.periodStart()),
            OffsetDateTime.parse(s.periodEnd()));
    }
    /** Source row must be trusted, same tenant/Marketplace/currency, not allocated elsewhere. */
    @Transactional
    public void link(UUID tenant,UUID mp,UUID actor,UUID statementId,UUID event) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Statement batch=get(tenant,mp,statementId,true);
        List<Candidate> found=jdbc.query("""
            SELECT id,source_event_key,source_type,amount,currency_code,posted_at
            FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
            FOR SHARE
            """,SettlementStatementService::candidate,tenant,mp,event);
        if(found.size()!=1)throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Eligible LIVE source transaction not found");
        if(!batch.currency().equals(found.getFirst().currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source currency and statement currency differ");
        List<UUID> prior=jdbc.query("""
            SELECT statement_id FROM finance_settlement_event_link
            WHERE tenant_id=? AND event_id=?
            """,(r,i)->r.getObject(1,UUID.class),tenant,event);
        if(!prior.isEmpty()){
            if(prior.getFirst().equals(statementId))return; // idempotent
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Transaction is already assigned to another statement");
        }
        try {
            jdbc.update("""
                INSERT INTO finance_settlement_event_link
                  (tenant_id,statement_id,event_id,linked_by)
                VALUES (?,?,?,?)
                """,tenant,statementId,event,actor);
        } catch(DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Transaction assignment changed concurrently");
        }
        audit(tenant,statementId,event,actor,"EVENT_LINKED");
    }
    @Transactional
    public void unlink(UUID tenant,UUID mp,UUID actor,UUID statementId,UUID event) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        get(tenant,mp,statementId,true);
        int changed=jdbc.update("""
            DELETE FROM finance_settlement_event_link
            WHERE tenant_id=? AND statement_id=? AND event_id=?
            """,tenant,statementId,event);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Transaction was not assigned to this statement");
        audit(tenant,statementId,event,actor,"EVENT_UNLINKED");
    }
    public List<Audit> auditHistory(UUID tenant,UUID mp,UUID statementId) {
        get(tenant,mp,statementId,false);
        return jdbc.query("""
            SELECT action,event_id,actor_user_id,occurred_at
            FROM finance_settlement_audit
            WHERE tenant_id=? AND statement_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString("action"),
                r.getObject("event_id",UUID.class)==null?null:
                    r.getObject("event_id",UUID.class).toString(),
                r.getObject("actor_user_id",UUID.class).toString(),
                time(r,"occurred_at")),tenant,statementId);
    }
    public SettlementReconciliationEngine.Preview preview(
        UUID tenant,UUID mp,UUID statementId) {
        Statement batch=get(tenant,mp,statementId,false);
        List<SettlementReconciliationEngine.Event> linked=jdbc.query("""
            SELECT f.id,f.source_event_key,f.source_type,f.amount,
              f.currency_code,f.posted_at
            FROM finance_settlement_event_link l
            JOIN finance_source_event f ON f.tenant_id=l.tenant_id AND f.id=l.event_id
            WHERE l.tenant_id=? AND l.statement_id=? AND f.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
            ORDER BY f.posted_at,f.id
            """,(r,i)->new SettlementReconciliationEngine.Event(
                r.getObject("id",UUID.class).toString(),r.getString("source_event_key"),
                r.getString("source_type"),r.getBigDecimal("amount"),
                r.getString("currency_code").trim(),at(r,"posted_at")),
            tenant,statementId,mp);
        Integer unassigned=jdbc.queryForObject("""
            SELECT COUNT(*) FROM finance_source_event f
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND f.currency_code=? AND f.posted_at>=? AND f.posted_at<?
              AND NOT EXISTS (SELECT 1 FROM finance_settlement_event_link l
                WHERE l.tenant_id=f.tenant_id AND l.event_id=f.id)
            """,Integer.class,tenant,mp,batch.currency(),
            OffsetDateTime.parse(batch.periodStart()),OffsetDateTime.parse(batch.periodEnd()));
        return SettlementReconciliationEngine.compare(
            new BigDecimal(batch.reportedNet()),batch.currency(),
            Instant.parse(batch.periodStart()),Instant.parse(batch.periodEnd()),
            linked,unassigned==null?0:unassigned);
    }
}
