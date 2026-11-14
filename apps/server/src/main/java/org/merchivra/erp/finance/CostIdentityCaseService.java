package org.merchivra.erp.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V166: persist and independently triage exact LOCAL source-identity candidates.
 * No financial charge equivalence claim, trusted invoice evidence, hold bypass,
 * settlement writes, GL posting or recognized net profit.
 */
@Service
public class CostIdentityCaseService {
    private final JdbcTemplate jdbc;
    public CostIdentityCaseService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Candidate(String signalCode,UUID candidateId) {}
    public record CaseView(String id,String sourceId,String signalCode,
        String candidateId,String state,String evidenceOrigin,
        String openedBy,String openedAt,String reviewedBy,String reviewedAt,
        boolean canClearFinancialHold,boolean externalProofVerified) {}
    public record Audit(String action,String actorId,String occurredAt) {}
    private record CaseRow(UUID id,UUID sourceId,String signalCode,UUID candidateId,
        String state,String origin,UUID opener,OffsetDateTime openedAt,
        UUID reviewer,OffsetDateTime reviewedAt) {}

    static void independent(UUID opener,UUID sourceMaker,UUID reviewer,
                            String disposition,String reason) {
        if(reviewer==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(opener==null||sourceMaker==null||reviewer.equals(opener)
            ||reviewer.equals(sourceMaker))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Cost collision reviewer must be independent");
        if(!List.of("ESCALATED_NEEDS_PROOF","POTENTIAL_DUPLICATE",
            "NOT_SAME_CHARGE_UNVERIFIED").contains(disposition)
            ||reason==null||reason.strip().length()<10||reason.length()>500
            ||reason.indexOf('\n')>=0||reason.indexOf('\r')>=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid unverified disposition or audit reason");
    }

    private UUID maker(UUID tenant,UUID mp,UUID source,boolean lock) {
        var found=jdbc.query("""
            SELECT s.created_by FROM finance_cost_source_v91 s
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            """+(lock?" FOR UPDATE OF s":""),
            (r,i)->r.getObject(1,UUID.class),tenant,mp,source);
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return found.getFirst();
    }
    private static CaseRow row(ResultSet r,int i)throws SQLException {
        return new CaseRow(r.getObject("id",UUID.class),
            r.getObject("source_id",UUID.class),r.getString("signal_code"),
            r.getObject("candidate_id",UUID.class),r.getString("state"),
            r.getString("evidence_origin"),r.getObject("opened_by",UUID.class),
            r.getObject("opened_at",OffsetDateTime.class),
            r.getObject("reviewed_by",UUID.class),
            r.getObject("reviewed_at",OffsetDateTime.class));
    }
    private static CaseView view(CaseRow b) {
        return new CaseView(b.id().toString(),b.sourceId().toString(),
            b.signalCode(),b.candidateId().toString(),b.state(),b.origin(),
            b.opener().toString(),b.openedAt().toInstant().toString(),
            b.reviewer()==null?null:b.reviewer().toString(),
            b.reviewedAt()==null?null:b.reviewedAt().toInstant().toString(),
            false,false);
    }
    private CaseRow find(UUID tenant,UUID source,UUID id,boolean lock) {
        var rows=jdbc.query("""
            SELECT * FROM finance_cost_identity_case_v93
            WHERE tenant_id=? AND source_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            CostIdentityCaseService::row,tenant,source,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private void audit(UUID tenant,UUID caseId,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO finance_cost_identity_case_audit_v93
                (id,tenant_id,case_id,action,actor_id)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,caseId,action,actor);
    }

    /**
     * Server-generated matched local candidate IDs; never accept caller-
     * supplied candidate identity. Bounded at 100, from one scoped snapshot.
     * Same key/CLAIMED SHA, not same charge or independent document evidence.
     */
    List<Candidate> candidates(UUID tenant,UUID mp,UUID sourceId) {
        return jdbc.query("""
            WITH source AS (
              SELECT s.* FROM finance_cost_source_v91 s
              WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            )
            SELECT 'OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT' AS code,c.id AS candidate
             FROM source s JOIN finance_cost_source_v91 c
              ON c.tenant_id=s.tenant_id
               AND c.seller_marketplace_id=s.seller_marketplace_id
               AND c.id<>s.id
               AND c.source_document_sha256=s.source_document_sha256
            UNION ALL
            SELECT 'MANUAL_ADS_SPEND_SAME_KEY',a.id
             FROM source s JOIN finance_ad_spend_reference a
              ON a.tenant_id=s.tenant_id
               AND a.seller_marketplace_id=s.seller_marketplace_id
               AND a.source_key=s.source_key
            UNION ALL
            SELECT 'OFFLINE_ADS_REPORT_SAME_REFERENCE',a.id
             FROM source s JOIN ads_report_import a
              ON a.tenant_id=s.tenant_id
               AND a.seller_marketplace_id=s.seller_marketplace_id
               AND a.report_reference=s.source_key AND a.state<>'REJECTED'
            UNION ALL
            SELECT 'OFFLINE_ADS_REPORT_SAME_CLAIMED_DOCUMENT',a.id
             FROM source s JOIN ads_report_import a
              ON a.tenant_id=s.tenant_id
               AND a.seller_marketplace_id=s.seller_marketplace_id
               AND a.report_sha256=s.source_document_sha256 AND a.state<>'REJECTED'
            UNION ALL
            SELECT 'SPAPI_FINANCES_EVENT_SAME_KEY',f.id
             FROM source s JOIN finance_source_event f
              ON f.tenant_id=s.tenant_id
               AND f.seller_marketplace_id=s.seller_marketplace_id
               AND f.source_family='SPAPI_FINANCES'
               AND f.source_event_key=s.source_key
            UNION ALL
            SELECT 'SPAPI_SETTLEMENT_REPORT_SAME_REFERENCE',r.id
             FROM source s JOIN spapi_settlement_report r
              ON r.tenant_id=s.tenant_id
               AND r.seller_marketplace_id=s.seller_marketplace_id
               AND (r.amazon_report_id=s.source_key
                OR r.report_document_id=s.source_key
                OR r.settlement_reference=s.source_key)
            UNION ALL
            SELECT 'SPAPI_SETTLEMENT_REPORT_SAME_CLAIMED_DOCUMENT',r.id
             FROM source s JOIN spapi_settlement_report r
              ON r.tenant_id=s.tenant_id
               AND r.seller_marketplace_id=s.seller_marketplace_id
               AND r.document_sha256=s.source_document_sha256
            UNION ALL
            SELECT 'MANUAL_SETTLEMENT_STATEMENT_SAME_REFERENCE',t.id
             FROM source s JOIN finance_settlement_statement t
              ON t.tenant_id=s.tenant_id
               AND t.seller_marketplace_id=s.seller_marketplace_id
               AND t.statement_ref=s.source_key
            ORDER BY code,candidate LIMIT 101
            """,(r,i)->new Candidate(r.getString("code"),
                r.getObject("candidate",UUID.class)),tenant,mp,sourceId);
    }

    @Transactional
    public List<CaseView> scan(UUID tenant,UUID mp,UUID source,UUID actor) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        maker(tenant,mp,source,true); // Serialize source-case snapshot scans.
        var matches=candidates(tenant,mp,source);
        if(matches.size()>100)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Too many local identity candidates; requires bounded investigation");
        for(var candidate:matches) {
            if(!CostIdentityReconciliationService.SIGNALS.contains(candidate.signalCode()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Unsupported source identity candidate");
            var prior=jdbc.query("""
                SELECT id FROM finance_cost_identity_case_v93
                WHERE tenant_id=? AND source_id=?
                  AND signal_code=? AND candidate_id=?
                """,(r,i)->r.getObject(1,UUID.class),
                tenant,source,candidate.signalCode(),candidate.candidateId());
            if(!prior.isEmpty())continue;
            UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finance_cost_identity_case_v93
                  (id,tenant_id,seller_marketplace_id,source_id,signal_code,
                   candidate_id,opened_by)
                VALUES (?,?,?,?,?,?,?)
                """,id,tenant,mp,source,candidate.signalCode(),
                candidate.candidateId(),actor);
            audit(tenant,id,actor,"OPEN");
        }
        return list(tenant,mp,source);
    }

    public List<CaseView> list(UUID tenant,UUID mp,UUID source) {
        maker(tenant,mp,source,false);
        return jdbc.query("""
            SELECT * FROM finance_cost_identity_case_v93
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_id=?
            ORDER BY opened_at DESC,id DESC LIMIT 100
            """,CostIdentityCaseService::row,tenant,mp,source)
            .stream().map(CostIdentityCaseService::view).toList();
    }

    @Transactional
    public CaseView decide(UUID tenant,UUID mp,UUID source,UUID id,
                           UUID reviewer,String disposition,String reason) {
        if(reviewer==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(!List.of("ESCALATED_NEEDS_PROOF","POTENTIAL_DUPLICATE",
            "NOT_SAME_CHARGE_UNVERIFIED").contains(disposition)
            ||reason==null||reason.strip().length()<10||reason.length()>500
            ||reason.indexOf('\n')>=0||reason.indexOf('\r')>=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid unverified disposition or review reason");
        UUID sourceMaker=maker(tenant,mp,source,true);
        CaseRow before=find(tenant,source,id,true);
        independent(before.opener(),sourceMaker,reviewer,disposition,reason);
        if(!"OPEN".equals(before.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cost collision decision already recorded");
        jdbc.update("""
            UPDATE finance_cost_identity_case_v93
            SET state=?,reviewed_by=?,reviewed_at=now(),review_note=?
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_id=?
              AND id=? AND state='OPEN'
            """,disposition,reviewer,reason.strip(),tenant,mp,source,id);
        audit(tenant,id,reviewer,disposition);
        return view(find(tenant,source,id,false));
    }

    public List<Audit> history(UUID tenant,UUID mp,UUID source,UUID id) {
        maker(tenant,mp,source,false);
        find(tenant,source,id,false);
        return jdbc.query("""
            SELECT action,actor_id,occurred_at
            FROM finance_cost_identity_case_audit_v93
            WHERE tenant_id=? AND case_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),
                r.getObject(2,UUID.class).toString(),
                r.getObject(3,OffsetDateTime.class).toInstant().toString()),
            tenant,id);
    }
}
