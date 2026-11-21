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
 * V182: live, source-scoped LOCAL attribution evidence classification.
 * Reuses V94 independent review + append-only audit, V95 shared-line holds
 * and V102 immutable captured identity. It neither creates another approval
 * workflow nor claims authentic Amazon charges, bank payout or real net profit.
 *
 * The classification is a read-time snapshot, not an immutable adjudication.
 * Whenever a new link or conflict appears it must be recalculated.
 */
@Service
public class CostAttributionEvidenceService {
    private final JdbcTemplate jdbc;
    private final CostIdentityReconciliationService reconciliation;

    public CostAttributionEvidenceService(JdbcTemplate jdbc,
        CostIdentityReconciliationService reconciliation) {
        this.jdbc=jdbc;
        this.reconciliation=reconciliation;
    }

    record Counts(long links,long frozen,long missingCapture,long independentlyReviewed,
        long auditBackedReviews,long auditBackedPotentialDuplicates,
        long exactReferenceAndAmount,long incompatibleLines,long sharedLines) {}

    public record Assessment(String sourceId,String status,List<String> reasonCodes,
        long linkedLineCount,long frozenIdentityCount,long missingCaptureCount,
        long independentlyReviewedCount,long auditBackedReviewCount,
        long strongLocalMatchCount,long sharedLineCount,long incompatibleLineCount,
        boolean independentReviewAuditComplete,boolean localCollisionApprovalHeld,
        boolean externalChargeVerified,boolean duplicateDebitVerified,
        boolean financialHoldCleared,boolean netProfitEligible) {}

    /**
     * Precedence: conflicting source/line evidence > narrowly supported local
     * metadata > unknown. A reviewer saying "potential duplicate" is ONLY an
     * independently audited hypothesis, not charge equivalence.
     */
    static Assessment classify(UUID sourceId,
        CostIdentityReconciliationService.Preview preview,Counts c) {
        if(sourceId==null||preview==null||c==null)
            throw new IllegalArgumentException("Missing cost attribution evidence");
        boolean consistent=c.links()>=0 && c.frozen()>=0
            && c.missingCapture()>=0 && c.independentlyReviewed()>=0
            && c.auditBackedReviews()>=0 && c.auditBackedPotentialDuplicates()>=0
            && c.exactReferenceAndAmount()>=0 && c.incompatibleLines()>=0
            && c.sharedLines()>=0 && c.frozen()+c.missingCapture()==c.links()
            && c.independentlyReviewed()<=c.links()
            && c.auditBackedReviews()<=c.independentlyReviewed()
            && c.auditBackedPotentialDuplicates()<=c.auditBackedReviews()
            && c.exactReferenceAndAmount()<=c.links()
            && c.incompatibleLines()<=c.links() && c.sharedLines()<=c.links()
            && c.links()==preview.linkedSettlementLineCount()
            && c.sharedLines()==preview.sharedSettlementLineCount()
            && sourceId.toString().equals(preview.sourceId());
        boolean reviewComplete=consistent && c.links()>0
            && c.independentlyReviewed()==c.links()
            && c.auditBackedReviews()==c.links();
        var reasons=new ArrayList<String>();
        String status;
        if(!consistent) {
            status="UNPROVEN";
            reasons.add("LOCAL_EVIDENCE_COUNTS_INCONSISTENT");
        } else {
            boolean localOverlap=preview.signals().stream()
                .anyMatch(CostIdentityReconciliationService.Signal::exactLocalIdentityOverlap);
            if(c.sharedLines()>0)reasons.add("SHARED_SETTLEMENT_LINE_CLAIM");
            if(localOverlap)reasons.add("POTENTIAL_CROSS_SOURCE_IDENTITY_OVERLAP");
            if(c.incompatibleLines()>0)reasons.add("CURRENCY_OR_DEBIT_DIRECTION_MISMATCH");
            if(c.sharedLines()>0||localOverlap||c.incompatibleLines()>0) {
                status="CONFLICT_EVIDENCE";
            } else {
                if(c.links()==0)reasons.add("NO_SETTLEMENT_LINE_ASSOCIATION");
                if(c.missingCapture()>0)reasons.add("CREATION_TIME_V102_CAPTURE_MISSING");
                if(c.links()>1)reasons.add("MULTIPLE_LINE_CLAIMS_REQUIRE_ALLOCATION_PROOF");
                if(c.independentlyReviewed()<c.links())
                    reasons.add("INDEPENDENT_V94_REVIEW_PENDING");
                if(c.auditBackedReviews()<c.independentlyReviewed())
                    reasons.add("V94_REVIEW_AUDIT_MISSING");
                if(c.exactReferenceAndAmount()!=1||c.links()!=1)
                    reasons.add("SINGLE_EXACT_REFERENCE_AND_AMOUNT_NOT_ESTABLISHED");
                if(c.auditBackedPotentialDuplicates()!=1||c.links()!=1)
                    reasons.add("NO_INDEPENDENT_AUDITED_POTENTIAL_DUPLICATE_REVIEW");
                boolean hasLocalSupport=c.links()==1 && c.frozen()==1
                    && c.exactReferenceAndAmount()==1 && reviewComplete
                    && c.auditBackedPotentialDuplicates()==1;
                status=hasLocalSupport
                    ?"SUPPORTING_LOCAL_METADATA_UNVERIFIED":"UNPROVEN";
                if(hasLocalSupport)reasons.add("MATCHING_LOCAL_LINE_AND_V94_REVIEW_ONLY");
            }
        }
        reasons.add("INDEPENDENT_EXTERNAL_CHARGE_PROOF_NOT_AVAILABLE");
        return new Assessment(sourceId.toString(),status,List.copyOf(reasons),
            c.links(),c.frozen(),c.missingCapture(),
            c.independentlyReviewed(),c.auditBackedReviews(),
            c.exactReferenceAndAmount(),c.sharedLines(),c.incompatibleLines(),
            reviewComplete,preview.approvalHeld(),
            false,false,false,false);
    }

    /**
     * Uses a single REPEATABLE READ database snapshot for the existing V165
     * collision signals, V94 reviewed links/audit and V102 captured identities.
     * There is intentionally no V182 write, backfill or auto-release.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Assessment assess(UUID tenant,UUID marketplace,UUID sourceId) {
        // Performs the existing tenant/marketplace/active-tenant scope check.
        var collision=reconciliation.preview(tenant,marketplace,sourceId);
        var rows=jdbc.query("""
            SELECT count(*) AS links,
              count(*) FILTER(WHERE i.link_id IS NOT NULL) AS frozen,
              count(*) FILTER(WHERE i.link_id IS NULL) AS missing_capture,
              count(*) FILTER(WHERE l.state<>'OPEN') AS reviewed,
              count(*) FILTER(WHERE l.state<>'OPEN' AND EXISTS(
                SELECT 1 FROM finance_cost_settlement_line_audit_v94 a
                WHERE a.tenant_id=l.tenant_id AND a.link_id=l.id
                  AND a.action=l.state AND a.actor_id=l.reviewed_by
              )) AS audited,
              count(*) FILTER(WHERE l.state='POTENTIAL_DUPLICATE_UNVERIFIED'
                AND EXISTS(SELECT 1 FROM finance_cost_settlement_line_audit_v94 a
                  WHERE a.tenant_id=l.tenant_id AND a.link_id=l.id
                    AND a.action=l.state AND a.actor_id=l.reviewed_by
              )) AS audited_potential,
              count(*) FILTER(WHERE
                l.similarity='SIMILAR_REFERENCE_AND_AMOUNT') AS exact_match,
              count(*) FILTER(WHERE l.similarity IN
                ('CURRENCY_MISMATCH','CREDIT_OR_ZERO_NOT_DEBIT')) AS incompatible,
              count(*) FILTER(WHERE EXISTS(
                SELECT 1 FROM finance_cost_settlement_line_link_v94 other
                WHERE other.tenant_id=l.tenant_id
                  AND other.seller_marketplace_id=l.seller_marketplace_id
                  AND other.report_id=l.report_id
                  AND other.line_number=l.line_number
                  AND other.source_id<>l.source_id
              )) AS shared
            FROM finance_cost_settlement_line_link_v94 l
            LEFT JOIN finance_cost_settlement_line_identity_v102 i
              ON i.tenant_id=l.tenant_id AND i.link_id=l.id
            WHERE l.tenant_id=? AND l.seller_marketplace_id=?
              AND l.source_id=?
            """,(r,i)->new Counts(r.getLong("links"),r.getLong("frozen"),
                r.getLong("missing_capture"),r.getLong("reviewed"),
                r.getLong("audited"),r.getLong("audited_potential"),
                r.getLong("exact_match"),r.getLong("incompatible"),
                r.getLong("shared")),tenant,marketplace,sourceId);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cost attribution evidence is unavailable");
        return classify(sourceId,collision,rows.getFirst());
    }
}
