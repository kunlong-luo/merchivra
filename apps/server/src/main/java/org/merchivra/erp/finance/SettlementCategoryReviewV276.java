package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** V276 internal review only. No controller, activation, V56 gate or ledger write.
 * One immutable request/decision per exact evidence. A revoked request is not
 * reopened under the same digest. All eligibility is point-in-RR-snapshot.
 */
@Service
public class SettlementCategoryReviewV276 {
    private final JdbcTemplate jdbc;
    private final SettlementReportComparisonService comparisons;
    private final RefundRecoveryService owners;
    public SettlementCategoryReviewV276(JdbcTemplate jdbc,SettlementReportComparisonService comparisons,
        RefundRecoveryService owners) {
        this.jdbc=jdbc;this.comparisons=comparisons;this.owners=owners;
    }
    public record Review(String mappingSha256,String evidenceSha256,String state,
        String requestedBy,String approvedBy,boolean mappingActivated,boolean bankVerified,
        boolean ledgerPosted,String realizedNetProfit) {}
    private record Request(UUID actor,String reason,OffsetDateTime at,UUID draftCreator) {}
    private record Approval(UUID actor,String reason,OffsetDateTime at) {}
    static final String REQUEST_SQL="""
        INSERT INTO finance_settlement_category_request_v125
          (tenant_id,report_id,mapping_sha256,evidence_sha256,requested_by,request_reason)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT (tenant_id,report_id,mapping_sha256,evidence_sha256) DO NOTHING
        """;
    static final String APPROVAL_SQL="""
        INSERT INTO finance_settlement_category_approval_v125
          (tenant_id,report_id,mapping_sha256,evidence_sha256,requested_by,approved_by,approval_reason)
        VALUES (?,?,?,?,?,?,?)
        ON CONFLICT (tenant_id,report_id,mapping_sha256,evidence_sha256) DO NOTHING
        """;
    static final String READ_REQUEST_SQL="""
        SELECT q.requested_by,q.request_reason,q.requested_at,d.created_by
        FROM finance_settlement_category_request_v125 q
        JOIN finance_settlement_category_mapping_v124 d ON d.tenant_id=q.tenant_id
          AND d.report_id=q.report_id AND d.mapping_sha256=q.mapping_sha256
        JOIN spapi_settlement_report r ON r.tenant_id=q.tenant_id AND r.id=q.report_id
        WHERE q.tenant_id=? AND r.seller_marketplace_id=? AND q.report_id=?
          AND q.mapping_sha256=? AND q.evidence_sha256=?
        """;
    static final String READ_APPROVAL_SQL="""
        SELECT a.approved_by,a.approval_reason,a.approved_at
        FROM finance_settlement_category_approval_v125 a
        JOIN spapi_settlement_report r ON r.tenant_id=a.tenant_id AND r.id=a.report_id
        WHERE a.tenant_id=? AND r.seller_marketplace_id=? AND a.report_id=?
          AND a.mapping_sha256=? AND a.evidence_sha256=?
        """;
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,"Category review evidence or actor changed");
    }
    private void requireEvidence(UUID tenant,UUID marketplace,UUID report,String mapping,String evidence) {
        if(tenant==null||marketplace==null||report==null||mapping==null||!mapping.matches("[0-9a-f]{64}")
            ||evidence==null||!evidence.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var now=comparisons.previewSavedCategoryMapping(tenant,marketplace,report,mapping);
        if(!mapping.equals(now.mappingSha256())||!evidence.equals(now.evidenceSha256())
            ||!"LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED".equals(now.preview().state()))throw conflict();
    }
    private Request requestRow(UUID tenant,UUID marketplace,UUID report,String mapping,String evidence) {
        return jdbc.query(READ_REQUEST_SQL,(rs,n)->new Request(rs.getObject(1,UUID.class),rs.getString(2),
            rs.getObject(3,OffsetDateTime.class),rs.getObject(4,UUID.class)),
            tenant,marketplace,report,mapping,evidence).stream().findFirst().orElse(null);
    }
    private Approval approvalRow(UUID tenant,UUID marketplace,UUID report,String mapping,String evidence) {
        return jdbc.query(READ_APPROVAL_SQL,(rs,n)->new Approval(rs.getObject(1,UUID.class),rs.getString(2),
            rs.getObject(3,OffsetDateTime.class)),tenant,marketplace,report,mapping,evidence)
            .stream().findFirst().orElse(null);
    }
    private void currentOwnerSince(UUID tenant,UUID actor,OffsetDateTime since) {
        owners.requireCurrentOwner(tenant,actor);
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM local_team_audit
              WHERE tenant_id=? AND subject_user_id=? AND event_code='OWNER_REVOKED'
                AND occurred_at>=?)
            """,Boolean.class,tenant,actor,since)))throw conflict();
    }
    static void independent(UUID requester,UUID creator,UUID approver) {
        if(requester==null||creator==null||approver==null||requester.equals(approver)||creator.equals(approver))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent category reviewer required");
    }
    private Review receipt(UUID tenant,String mapping,String evidence,Request request,Approval approval) {
        if(request==null)throw conflict();
        currentOwnerSince(tenant,request.actor(),request.at());
        if(approval!=null) {
            independent(request.actor(),request.draftCreator(),approval.actor());
            if(approval.at().isBefore(request.at()))throw conflict();
            currentOwnerSince(tenant,approval.actor(),approval.at());
        }
        return new Review(mapping,evidence,approval==null?"LOCAL_CATEGORY_REVIEW_REQUESTED":
            "LOCAL_CATEGORY_REVIEW_RECORDED_NOT_ACTIVATED",request.actor().toString(),
            approval==null?null:approval.actor().toString(),false,false,false,null);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Review request(UUID tenant,UUID marketplace,UUID report,String mapping,String evidence,
        UUID actor,String note) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String reason=SettlementSourceReviewService.validatedReason(note);
        owners.requireCurrentOwner(tenant,actor);
        requireEvidence(tenant,marketplace,report,mapping,evidence);
        jdbc.update(REQUEST_SQL,tenant,report,mapping,evidence,actor,reason);
        var saved=requestRow(tenant,marketplace,report,mapping,evidence);
        if(saved==null||!actor.equals(saved.actor())||!reason.equals(saved.reason()))throw conflict();
        return receipt(tenant,mapping,evidence,saved,approvalRow(tenant,marketplace,report,mapping,evidence));
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Review approve(UUID tenant,UUID marketplace,UUID report,String mapping,String evidence,
        UUID actor,String note) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String reason=SettlementSourceReviewService.validatedReason(note);
        owners.requireCurrentOwner(tenant,actor);
        requireEvidence(tenant,marketplace,report,mapping,evidence);
        var request=requestRow(tenant,marketplace,report,mapping,evidence);
        if(request==null)throw conflict();
        currentOwnerSince(tenant,request.actor(),request.at());
        independent(request.actor(),request.draftCreator(),actor);
        jdbc.update(APPROVAL_SQL,tenant,report,mapping,evidence,request.actor(),actor,reason);
        var saved=approvalRow(tenant,marketplace,report,mapping,evidence);
        if(saved==null||!actor.equals(saved.actor())||!reason.equals(saved.reason()))throw conflict();
        return receipt(tenant,mapping,evidence,request,saved);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Review current(UUID tenant,UUID marketplace,UUID report,String mapping,String evidence,UUID actor) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        owners.requireCurrentOwner(tenant,actor);
        requireEvidence(tenant,marketplace,report,mapping,evidence);
        return receipt(tenant,mapping,evidence,requestRow(tenant,marketplace,report,mapping,evidence),
            approvalRow(tenant,marketplace,report,mapping,evidence));
    }
}
