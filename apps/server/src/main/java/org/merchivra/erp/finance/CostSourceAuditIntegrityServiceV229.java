package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V229: independent V91 source-stage/review audit completeness preflight.
 * No new source of truth: re-read existing append-only V91 audit on the SAME
 * PostgreSQL snapshot as source state, instead of trusting a displayed status.
 * Completeness of LOCAL records NEVER attests the claimed document's bytes,
 * issuer/settlement origin, verified costs, COGS or recognized net profit.
 */
@Service
public class CostSourceAuditIntegrityServiceV229 {
    private final JdbcTemplate jdbc;
    public CostSourceAuditIntegrityServiceV229(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    public record Counts(long auditEvents,long matchingStageEvents,
        long matchingDecisionEvents) {}
    public record Evidence(String sourceId,String marketplaceId,
        String reviewState,long auditEventCount,boolean stageAuditIntact,
        boolean independentDecisionAuditIntact,boolean localAuditComplete,
        List<String> blockerCodes,boolean claimedDocumentBytesVerified,
        boolean independentExternalIssuerVerified,boolean externalChargeVerified,
        boolean actualCogsVerified,boolean eligibleForRealizedProfit,
        String realizedNetProfit) {}

    /** No audit count is inferred from a source state. Unexpected/extra events
     * are treated as an inconsistency and never silently discarded. */
    static Evidence evaluate(UUID sourceId,UUID mp,String state,
        UUID maker,UUID reviewer,Counts c) {
        if(sourceId==null||mp==null||state==null||maker==null||c==null)
            throw new IllegalArgumentException("Missing V91 source/audit snapshot");
        boolean terminal="REVIEWED_UNVERIFIED".equals(state)
            ||"REJECTED".equals(state);
        boolean stage=c.matchingStageEvents()==1;
        boolean independent=terminal && reviewer!=null && !reviewer.equals(maker)
            && c.matchingDecisionEvents()==1;
        boolean complete=stage
            && (terminal?independent:("STAGED".equals(state)&&reviewer==null
                && c.matchingDecisionEvents()==0))
            && c.auditEvents()==(terminal?2:1);
        var blockers=new ArrayList<String>();
        if(!stage)blockers.add("V91_STAGED_AUDIT_MISSING_OR_DUPLICATED");
        if(terminal&&!independent)
            blockers.add("V91_INDEPENDENT_DECISION_AUDIT_MISSING_OR_MISMATCHED");
        if(!"STAGED".equals(state)&&!terminal)
            blockers.add("V91_SOURCE_STATE_UNKNOWN");
        if(!complete)blockers.add("V91_SOURCE_AUDIT_INCONSISTENT");
        if("STAGED".equals(state))
            blockers.add("V91_INDEPENDENT_METADATA_REVIEW_PENDING");
        if("REJECTED".equals(state))
            blockers.add("V91_SOURCE_REJECTED");
        blockers.add("CLAIMED_DOCUMENT_SHA256_NOT_BYTES_VERIFIED");
        blockers.add("INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED");
        blockers.add("EXTERNAL_CHARGE_NOT_RECONCILED");
        blockers.add("REAL_COGS_AND_PROFIT_POLICY_NOT_APPROVED");
        return new Evidence(sourceId.toString(),mp.toString(),state,
            c.auditEvents(),stage,independent,complete,List.copyOf(blockers),
            false,false,false,false,false,null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Evidence inspect(UUID tenant,UUID marketplace,UUID sourceId) {
        if(tenant==null||marketplace==null||sourceId==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // One bounded tenant + Marketplace + source query. Collapses the audit
        // before output: no raw invoice, account, digest or reviewer note leaks.
        var rows=jdbc.query("""
            SELECT s.id,s.seller_marketplace_id,s.review_state,
              s.created_by,s.reviewed_by,
              count(a.id) AS audit_count,
              count(a.id) FILTER(WHERE a.action='STAGED'
                AND a.actor_id=s.created_by) AS stage_count,
              count(a.id) FILTER(WHERE a.action=s.review_state
                AND a.actor_id=s.reviewed_by
                AND a.action IN ('REVIEWED_UNVERIFIED','REJECTED'))
                AS decision_count
            FROM finance_cost_source_v91 s
            JOIN seller_marketplace m ON m.id=s.seller_marketplace_id
              AND m.tenant_id=s.tenant_id
            JOIN seller_account sa ON sa.id=m.seller_account_id
              AND sa.tenant_id=s.tenant_id
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            LEFT JOIN finance_cost_source_audit_v91 a
              ON a.tenant_id=s.tenant_id AND a.source_id=s.id
            WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            GROUP BY s.id,s.seller_marketplace_id,s.review_state,
              s.created_by,s.reviewed_by
            """,(r,i)->evaluate(r.getObject("id",UUID.class),
                r.getObject("seller_marketplace_id",UUID.class),
                r.getString("review_state"),
                r.getObject("created_by",UUID.class),
                r.getObject("reviewed_by",UUID.class),
                new Counts(r.getLong("audit_count"),
                  r.getLong("stage_count"),r.getLong("decision_count"))),
            tenant,marketplace,sourceId);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
}
