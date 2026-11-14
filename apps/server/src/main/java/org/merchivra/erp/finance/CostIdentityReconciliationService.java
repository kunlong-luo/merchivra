package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * V165 snapshot-only potential identity collision guard. Exact matching keys
 * in DIFFERENT source namespaces are NOT proof of one financial charge.
 *
 * No remote API, no canonical invoice truth, no accounting writes. Absence of
 * rows NEVER proves no duplication, complete source coverage or real profit.
 */
@Service
public class CostIdentityReconciliationService {
    private final JdbcTemplate jdbc;
    public CostIdentityReconciliationService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Signal(String code, boolean exactLocalIdentityOverlap) {}
    public record Preview(String sourceId,String sourceState,String status,
        List<Signal> signals,boolean approvalHeld,
        boolean independentExternalEvidenceVerified,
        boolean completeSourceCoverage,boolean netProfitVerified) {}

    static final List<String> SIGNALS=List.of(
        "OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT",
        "MANUAL_ADS_SPEND_SAME_KEY",
        "OFFLINE_ADS_REPORT_SAME_REFERENCE",
        "OFFLINE_ADS_REPORT_SAME_CLAIMED_DOCUMENT",
        "SPAPI_FINANCES_EVENT_SAME_KEY",
        "SPAPI_SETTLEMENT_REPORT_SAME_REFERENCE",
        "SPAPI_SETTLEMENT_REPORT_SAME_CLAIMED_DOCUMENT",
        "MANUAL_SETTLEMENT_STATEMENT_SAME_REFERENCE");

    static Preview summarize(UUID sourceId,String state,List<Boolean> matches) {
        if(sourceId==null||state==null||matches==null||matches.size()!=SIGNALS.size()
           ||matches.stream().anyMatch(x->x==null))
            throw new IllegalArgumentException("Invalid provenance snapshot");
        var items=new ArrayList<Signal>();
        boolean flagged=false;
        for(int i=0;i<SIGNALS.size();i++) {
            boolean match=matches.get(i);
            items.add(new Signal(SIGNALS.get(i),match));
            flagged|=match;
        }
        return new Preview(sourceId.toString(),state,
            flagged?"POTENTIAL_IDENTITY_COLLISION_HOLD":"NO_LOCAL_IDENTITY_MATCH_UNVERIFIED",
            List.copyOf(items),flagged,false,false,false);
    }

    public Preview preview(UUID tenant,UUID marketplace,UUID sourceId) {
        // Explicit same-tenant and same-Marketplace comparisons. These are
        // potential source-identity overlaps, never verified fee equivalence.
        // In particular a report-level digest != proof of a fee-level charge.
        var found=jdbc.query("""
            SELECT s.id,s.review_state,
              EXISTS(SELECT 1 FROM finance_cost_source_v91 c
                WHERE c.tenant_id=s.tenant_id
                  AND c.seller_marketplace_id=s.seller_marketplace_id
                  AND c.id<>s.id
                  AND c.source_document_sha256=s.source_document_sha256),
              EXISTS(SELECT 1 FROM finance_ad_spend_reference a
                WHERE a.tenant_id=s.tenant_id
                  AND a.seller_marketplace_id=s.seller_marketplace_id
                  AND a.source_key=s.source_key),
              EXISTS(SELECT 1 FROM ads_report_import a
                WHERE a.tenant_id=s.tenant_id
                  AND a.seller_marketplace_id=s.seller_marketplace_id
                  AND a.report_reference=s.source_key
                  AND a.state<>'REJECTED'),
              EXISTS(SELECT 1 FROM ads_report_import a
                WHERE a.tenant_id=s.tenant_id
                  AND a.seller_marketplace_id=s.seller_marketplace_id
                  AND a.report_sha256=s.source_document_sha256
                  AND a.state<>'REJECTED'),
              EXISTS(SELECT 1 FROM finance_source_event f
                WHERE f.tenant_id=s.tenant_id
                  AND f.seller_marketplace_id=s.seller_marketplace_id
                  AND f.source_family='SPAPI_FINANCES'
                  AND f.source_event_key=s.source_key),
              EXISTS(SELECT 1 FROM spapi_settlement_report r
                WHERE r.tenant_id=s.tenant_id
                  AND r.seller_marketplace_id=s.seller_marketplace_id
                  AND (r.amazon_report_id=s.source_key
                    OR r.report_document_id=s.source_key
                    OR r.settlement_reference=s.source_key)),
              EXISTS(SELECT 1 FROM spapi_settlement_report r
                WHERE r.tenant_id=s.tenant_id
                  AND r.seller_marketplace_id=s.seller_marketplace_id
                  AND r.document_sha256=s.source_document_sha256),
              EXISTS(SELECT 1 FROM finance_settlement_statement t
                WHERE t.tenant_id=s.tenant_id
                  AND t.seller_marketplace_id=s.seller_marketplace_id
                  AND t.statement_ref=s.source_key)
            FROM finance_cost_source_v91 s
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            JOIN seller_marketplace m ON m.tenant_id=s.tenant_id
                AND m.id=s.seller_marketplace_id
            WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            """,(r,i)->summarize(r.getObject(1,UUID.class),r.getString(2),
                List.of(r.getBoolean(3),r.getBoolean(4),r.getBoolean(5),
                    r.getBoolean(6),r.getBoolean(7),r.getBoolean(8),
                    r.getBoolean(9),r.getBoolean(10))),
            tenant,marketplace,sourceId);
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return found.getFirst();
    }

    /** Re-evaluate under the caller's V164 transaction/source lock at decision. */
    public void requireNoPotentialOverlap(UUID tenant,UUID marketplace,UUID sourceId) {
        if(preview(tenant,marketplace,sourceId).approvalHeld())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Potential cross-source cost identity overlap needs independent resolution");
    }
}
