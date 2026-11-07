package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Dual-control of a MANUAL source. REVIEWED never means bankVerified. */
@Service
public class BankPayoutManualReviewService {
    private final JdbcTemplate jdbc;
    private final BankPayoutComparisonService comparisons;
    private final SettlementSourceReviewService sourceReviews;
    public BankPayoutManualReviewService(JdbcTemplate jdbc,
        BankPayoutComparisonService comparisons,
        SettlementSourceReviewService sourceReviews) {
        this.jdbc=jdbc;this.comparisons=comparisons;this.sourceReviews=sourceReviews;
    }
    public record Status(String state,String comparisonState,
        String requestedBy,String reviewedBy,int version,boolean evidenceCurrent,
        boolean bankVerified,boolean ledgerPosted) {}
    public record Audit(String action,int version,String actorId,String reason,
        String occurredAt) {}
    private record Source(UUID payoutId,UUID importedBy,
        String bankDigest,String reportDigest) {}
    private record Evidence(String fingerprint,String comparisonState,
        UUID payoutId,UUID importedBy) {}
    private record Stored(String state,String digest,UUID payoutId,
        UUID requester,UUID reviewer,int version) {}

    static String digest(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private Evidence evidence(UUID tenant,UUID mp,UUID report) {
        var comparison=comparisons.compare(tenant,mp,report);
        if(comparison.candidateCount()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exactly one manual bank entry required for review");
        var selected=comparison.candidates().getFirst();
        List<Source> found=jdbc.query("""
            SELECT b.id,i.imported_by,i.file_sha256,r.document_sha256
            FROM bank_payout_observation b
            JOIN bank_payout_import_batch i
              ON i.tenant_id=b.tenant_id AND i.id=b.batch_id
              AND i.seller_marketplace_id=b.seller_marketplace_id
            JOIN spapi_settlement_report r ON r.tenant_id=b.tenant_id
              AND r.seller_marketplace_id=b.seller_marketplace_id
              AND r.settlement_reference=b.settlement_ref
            WHERE b.tenant_id=? AND b.seller_marketplace_id=?
              AND r.id=? AND b.entry_ref=?
            """,(r,n)->new Source(r.getObject("id",UUID.class),
                r.getObject("imported_by",UUID.class),r.getString("file_sha256"),
                r.getString("document_sha256")),
            tenant,mp,report,selected.entryRef());
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Manual bank evidence no longer unique");
        var source=found.getFirst();
        var reviewed=sourceReviews.status(tenant,mp,report);
        String fingerprint=digest(String.join("|",
            report.toString(),mp.toString(),comparison.settlementRef(),
            comparison.settlementCurrency(),String.valueOf(comparison.declaredNet()),
            comparison.state(),String.valueOf(comparison.sourceDifference()),
            selected.entryRef(),selected.valueDate(),selected.currency(),
            selected.credit(),selected.withheldFee(),
            String.valueOf(selected.declaredFxRate()),source.bankDigest(),
            source.reportDigest(),reviewed.state(),
            Integer.toString(reviewed.version()),
            Boolean.toString(reviewed.evidenceCurrent())));
        return new Evidence(fingerprint,comparison.state(),
            source.payoutId(),source.importedBy());
    }
    private Stored stored(UUID tenant,UUID mp,UUID report,boolean lock) {
        return jdbc.query("""
            SELECT v.state,v.evidence_sha256,v.payout_entry_id,
              v.requested_by,v.reviewed_by,v.version
            FROM bank_payout_manual_review v
            WHERE v.tenant_id=? AND v.seller_marketplace_id=? AND v.report_id=?
            """+(lock?" FOR UPDATE":""),
            (r,n)->new Stored(r.getString("state"),r.getString("evidence_sha256"),
                r.getObject("payout_entry_id",UUID.class),
                r.getObject("requested_by",UUID.class),
                r.getObject("reviewed_by",UUID.class),r.getInt("version")),
            tenant,mp,report).stream().findFirst().orElse(null);
    }
    private static Status display(Stored stored,Evidence source) {
        if(stored==null)return new Status("NOT_REQUESTED",
            source.comparisonState(),null,null,0,false,false,false);
        boolean fresh=stored.digest().equals(source.fingerprint())
            && stored.payoutId().equals(source.payoutId());
        return new Status(fresh?stored.state():"STALE",source.comparisonState(),
            stored.requester().toString(),
            stored.reviewer()==null?null:stored.reviewer().toString(),
            stored.version(),fresh,false,false);
    }
    @Transactional(readOnly=true)
    public Status status(UUID tenant,UUID mp,UUID report) {
        var current=evidence(tenant,mp,report);
        return display(stored(tenant,mp,report,false),current);
    }
    private void audit(UUID tenant,UUID report,String action,int version,
        Evidence source,UUID actor,String note) {
        jdbc.update("""
            INSERT INTO bank_payout_manual_review_audit
              (id,tenant_id,report_id,review_version,actor_user_id,
               action,evidence_sha256,reason)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,report,version,actor,action,
            source.fingerprint(),note);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Status request(UUID tenant,UUID mp,UUID report,UUID actor,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=SettlementSourceReviewService.validatedReason(reason);
        var current=evidence(tenant,mp,report);
        var old=stored(tenant,mp,report,true);
        if(old!=null&&"PENDING".equals(old.state())
            &&old.digest().equals(current.fingerprint()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Existing bank manual review awaiting second Owner");
        int version=old==null?1:old.version()+1;
        if(old==null) {
            try {
                jdbc.update("""
                    INSERT INTO bank_payout_manual_review
                      (tenant_id,seller_marketplace_id,report_id,payout_entry_id,
                       state,evidence_sha256,requested_by,version)
                    VALUES (?,?,?,?,'PENDING',?,?,?)
                    """,tenant,mp,report,current.payoutId(),
                    current.fingerprint(),actor,version);
            }catch(DuplicateKeyException e){
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Manual bank review or payout ID already assigned");
            }
        }else{
            jdbc.update("""
                UPDATE bank_payout_manual_review SET payout_entry_id=?,
                    state='PENDING',evidence_sha256=?,requested_by=?,
                    requested_at=now(),reviewed_by=NULL,reviewed_at=NULL,version=?
                WHERE tenant_id=? AND seller_marketplace_id=? AND report_id=?
                """,current.payoutId(),current.fingerprint(),actor,version,
                tenant,mp,report);
        }
        audit(tenant,report,old==null?"REQUESTED":"REOPENED",version,
            current,actor,note);
        return display(stored(tenant,mp,report,false),current);
    }

    static void ensureIndependent(UUID requester,UUID importer,UUID reviewer) {
        if(reviewer==null || reviewer.equals(requester)||reviewer.equals(importer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Reviewer must differ from both applicant and bank data importer");
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Status decide(UUID tenant,UUID mp,UUID report,UUID actor,
        boolean accept,String reason) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=SettlementSourceReviewService.validatedReason(reason);
        var old=stored(tenant,mp,report,true);
        if(old==null||!"PENDING".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending manual bank review");
        var current=evidence(tenant,mp,report);
        ensureIndependent(old.requester(),current.importedBy(),actor);
        if(!old.digest().equals(current.fingerprint())
            || !old.payoutId().equals(current.payoutId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Manual bank or Amazon source evidence changed");
        if(accept && !"MANUAL_AMOUNT_MATCH_UNVERIFIED".equals(current.comparisonState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only identical same-currency manual amounts may be checked");
        int changed=jdbc.update("""
            UPDATE bank_payout_manual_review SET
               state=?,reviewed_by=?,reviewed_at=now()
            WHERE tenant_id=? AND seller_marketplace_id=? AND report_id=?
              AND state='PENDING' AND version=?
            """,accept?"REVIEWED":"REJECTED",actor,
            tenant,mp,report,old.version());
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Bank review changed concurrently");
        audit(tenant,report,accept?"REVIEWED":"REJECTED",old.version(),
            current,actor,note);
        return display(stored(tenant,mp,report,false),current);
    }
    @Transactional(readOnly=true)
    public List<Audit> history(UUID tenant,UUID mp,UUID report) {
        // Bounded tenant+Marketplace evidence check before exposing audit.
        evidence(tenant,mp,report);
        return jdbc.query("""
            SELECT a.action,a.review_version,a.actor_user_id,a.reason,a.occurred_at
            FROM bank_payout_manual_review_audit a
            JOIN bank_payout_manual_review r ON r.tenant_id=a.tenant_id
              AND r.report_id=a.report_id
            WHERE a.tenant_id=? AND r.seller_marketplace_id=? AND a.report_id=?
            ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100
            """,(r,n)->new Audit(r.getString("action"),r.getInt("review_version"),
                r.getObject("actor_user_id",UUID.class).toString(),
                r.getString("reason"),
                r.getObject("occurred_at",OffsetDateTime.class).toInstant().toString()),
            tenant,mp,report);
    }
    @Transactional(readOnly=true)
    public org.merchivra.erp.purchasing.LocalKeysetPage.Page<Audit> historyPage(
        UUID tenant,UUID mp,UUID report,int limit,String cursor) {
        evidence(tenant,mp,report); // preserve manual-bank evidence/Marketplace gate
        return FinanceAuditPages.read(jdbc,tenant,mp,report,"BANK",limit,cursor,"""
            SELECT a.id,a.action,a.review_version,a.actor_user_id,a.reason,a.occurred_at
            FROM bank_payout_manual_review_audit a
            JOIN bank_payout_manual_review r ON r.tenant_id=a.tenant_id
              AND r.report_id=a.report_id
            WHERE a.tenant_id=? AND r.seller_marketplace_id=? AND a.report_id=?
            """,(r,i)->new Audit(r.getString("action"),r.getInt("review_version"),
                r.getObject("actor_user_id",UUID.class).toString(),
                r.getString("reason"),
                r.getObject("occurred_at",OffsetDateTime.class).toInstant().toString()));
    }

}
