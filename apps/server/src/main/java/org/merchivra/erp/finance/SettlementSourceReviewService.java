package org.merchivra.erp.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable maker-checker review of independent source-evidence matching only.
 * No bank verification, settlement payout, Amazon mutation, or ledger posting.
 * A changed Finances set invalidates the prior approval on read and approval.
 */
@Service
public class SettlementSourceReviewService {
    private final JdbcTemplate jdbc;
    private final SettlementReportComparisonService comparisons;
    public SettlementSourceReviewService(JdbcTemplate jdbc,
        SettlementReportComparisonService comparisons) {
        this.jdbc=jdbc;this.comparisons=comparisons;
    }

    public record Review(String state,String sourceState,String requestedBy,
        String decidedBy,String requestedAt,String decidedAt,int version,
        boolean evidenceCurrent,boolean bankVerified,boolean ledgerPosted) {}
    public record Audit(String action,String sourceState,String actorId,
        String reason,int version,String occurredAt) {}
    private record Stored(String state,String sourceState,String evidence,
        UUID requester,UUID decider,OffsetDateTime requestedAt,
        OffsetDateTime decidedAt,int version) {}
    private record Evidence(String status,String fingerprint) {}

    // Only stable non-PII source facts are hashed. Every matching LIVE event
    // (including different-currency references) participates in the digest.
    // The report SHA ties the snapshot to the immutable document bytes.
    static final String EVIDENCE_SQL="""
        SELECT encode(sha256(convert_to(
          r.document_sha256 || ':' || r.settlement_reference || ':' ||
          r.statement_currency || ':' ||
          COALESCE((
            SELECT string_agg(
                f.id::text || ':' || f.amount::text || ':' ||
                f.currency_code::text || ':' || f.posted_at::text,
                '|' ORDER BY f.id)
            FROM finance_transaction_reference ref
            JOIN finance_source_event f ON f.tenant_id=ref.tenant_id
              AND f.id=ref.event_id
            WHERE ref.tenant_id=r.tenant_id
              AND ref.reference_kind='SETTLEMENT_ID'
              AND ref.reference_value=r.settlement_reference
              AND f.seller_marketplace_id=r.seller_marketplace_id
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
          ),''), 'UTF8')), 'hex') AS fingerprint
        FROM spapi_settlement_report r
        WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?
        """;

    private Evidence evidence(UUID tenant,UUID mp,UUID report) {
        var comparison=comparisons.compare(tenant,mp,report);
        String fingerprint=jdbc.query(EVIDENCE_SQL,(r,n)->
            r.getString("fingerprint"),tenant,mp,report)
            .stream().findFirst().orElseThrow(()->
                new ResponseStatusException(HttpStatus.NOT_FOUND,"Report missing"));
        return new Evidence(comparison.state(),fingerprint);
    }
    private Stored stored(UUID tenant,UUID mp,UUID report,boolean lock) {
        return jdbc.query("""
            SELECT v.state,v.source_state,v.evidence_sha256,v.requested_by,
              v.decided_by,v.requested_at,v.decided_at,v.version
            FROM finance_settlement_source_review v
            JOIN spapi_settlement_report r ON r.tenant_id=v.tenant_id
              AND r.id=v.report_id
            WHERE v.tenant_id=? AND v.seller_marketplace_id=?
              AND v.report_id=? AND r.seller_marketplace_id=?
            """+(lock?" FOR UPDATE OF v":""),
            SettlementSourceReviewService::map,tenant,mp,report,mp)
            .stream().findFirst().orElse(null);
    }
    private static Stored map(ResultSet r,int n)throws SQLException {
        return new Stored(r.getString("state"),r.getString("source_state"),
            r.getString("evidence_sha256"),r.getObject("requested_by",UUID.class),
            r.getObject("decided_by",UUID.class),
            r.getObject("requested_at",OffsetDateTime.class),
            r.getObject("decided_at",OffsetDateTime.class),
            r.getInt("version"));
    }
    private static String date(OffsetDateTime value) {
        return value==null?null:value.toInstant().toString();
    }
    private static Review display(Stored stored,Evidence current) {
        if(stored==null)return new Review("NOT_REQUESTED",current.status(),
            null,null,null,null,0,false,false,false);
        boolean fresh=stored.evidence().equals(current.fingerprint())
            && stored.sourceState().equals(current.status());
        return new Review(fresh?stored.state():"STALE",current.status(),
            stored.requester().toString(),
            stored.decider()==null?null:stored.decider().toString(),
            date(stored.requestedAt()),date(stored.decidedAt()),
            stored.version(),fresh,false,false);
    }

    @Transactional(readOnly=true)
    public Review status(UUID tenant,UUID mp,UUID report) {
        Evidence current=evidence(tenant,mp,report);
        return display(stored(tenant,mp,report,false),current);
    }

    static String validatedReason(String note) {
        if(note==null||note.strip().length()<10||note.length()>500
            ||note.isBlank()||note.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Review requires a 10-500 character non-sensitive rationale");
        return note.strip();
    }
    private void audit(UUID tenant,UUID report,int version,String action,
        Evidence current,UUID actor,String reason) {
        jdbc.update("""
            INSERT INTO finance_settlement_source_review_audit
              (id,tenant_id,report_id,review_version,action,source_state,
               evidence_sha256,actor_user_id,reason)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,report,version,action,
            current.status(),current.fingerprint(),actor,reason);
    }

    /** An Owner may request a review even for mismatched amounts, but cannot approve it. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Review request(UUID tenant,UUID mp,UUID report,UUID actor,String note) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String reason=validatedReason(note);
        Evidence current=evidence(tenant,mp,report);
        Stored old=stored(tenant,mp,report,true);
        if(old!=null && old.state().equals("PENDING")
            && old.evidence().equals(current.fingerprint())
            && old.sourceState().equals(current.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Existing review awaiting independent approval");
        int version=old==null?1:old.version()+1;
        if(old==null) {
            try {
                jdbc.update("""
                    INSERT INTO finance_settlement_source_review
                      (tenant_id,report_id,seller_marketplace_id,state,source_state,
                       evidence_sha256,requested_by,version)
                    VALUES (?,?,?,'PENDING',?,?,?,?)
                    """,tenant,report,mp,current.status(),
                    current.fingerprint(),actor,version);
            } catch(DuplicateKeyException concurrent) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Review already created by another Owner");
            }
        } else {
            jdbc.update("""
                UPDATE finance_settlement_source_review SET state='PENDING',
                  source_state=?,evidence_sha256=?,requested_by=?,
                  requested_at=now(),decided_by=NULL,decided_at=NULL,version=?
                WHERE tenant_id=? AND seller_marketplace_id=? AND report_id=?
                """,current.status(),current.fingerprint(),actor,version,
                tenant,mp,report);
        }
        audit(tenant,report,version,old==null?"REQUESTED":"REOPENED",
            current,actor,reason);
        return display(stored(tenant,mp,report,false),current);
    }

    static void requireIndependentReviewer(UUID requester,UUID reviewer) {
        if(requester==null || reviewer==null || requester.equals(reviewer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Requester cannot decide own finance source review");
    }

    /** A different Owner must approve, on unmodified matching source evidence. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Review decide(UUID tenant,UUID mp,UUID report,UUID actor,
        boolean approve,String note) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String reason=validatedReason(note);
        Stored old=stored(tenant,mp,report,true);
        if(old==null||!"PENDING".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending source review");
        requireIndependentReviewer(old.requester(),actor);
        Evidence current=evidence(tenant,mp,report);
        if(!old.evidence().equals(current.fingerprint())
            || !old.sourceState().equals(current.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source evidence has changed; request a new review");
        if(approve && !"SOURCE_AMOUNTS_AGREE_UNVERIFIED".equals(current.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only matching-source evidence can be reviewed as agreeing");
        if(approve) {
            Integer duplicates=jdbc.queryForObject("""
                SELECT count(*) FROM spapi_settlement_report r
                JOIN spapi_settlement_report target ON target.tenant_id=r.tenant_id
                  AND target.seller_marketplace_id=r.seller_marketplace_id
                  AND target.settlement_reference=r.settlement_reference
                WHERE target.tenant_id=? AND target.seller_marketplace_id=?
                  AND target.id=?
                """,Integer.class,tenant,mp,report);
            if(duplicates==null||duplicates!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Multiple report documents share this settlement ID");
        }
        int changed=jdbc.update("""
            UPDATE finance_settlement_source_review
            SET state=?,decided_by=?,decided_at=now()
            WHERE tenant_id=? AND seller_marketplace_id=? AND report_id=?
              AND state='PENDING' AND version=?
            """,approve?"APPROVED":"REJECTED",actor,tenant,mp,report,old.version());
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Source review changed concurrently");
        audit(tenant,report,old.version(),approve?"APPROVED":"REJECTED",
            current,actor,reason);
        return display(stored(tenant,mp,report,false),current);
    }

    @Transactional(readOnly=true)
    public List<Audit> history(UUID tenant,UUID mp,UUID report) {
        evidence(tenant,mp,report); // do not leak report existence across Marketplace
        return jdbc.query("""
            SELECT a.action,a.source_state,a.actor_user_id,a.reason,
                   a.review_version,a.occurred_at
            FROM finance_settlement_source_review_audit a
            JOIN spapi_settlement_report r ON r.tenant_id=a.tenant_id
              AND r.id=a.report_id
            WHERE a.tenant_id=? AND r.seller_marketplace_id=? AND a.report_id=?
            ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100
            """,(r,n)->new Audit(r.getString("action"),r.getString("source_state"),
                r.getObject("actor_user_id",UUID.class).toString(),
                r.getString("reason"),r.getInt("review_version"),
                date(r.getObject("occurred_at",OffsetDateTime.class))),
            tenant,mp,report);
    }
}
