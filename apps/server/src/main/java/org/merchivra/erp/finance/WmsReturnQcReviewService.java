package org.merchivra.erp.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * V175-A: append-only MANUAL warehouse QC evidence and independent triage.
 * Neither signature nor QC acknowledgement verifies a WMS operator or physical
 * receipt. Absolutely no stock, cost, ledger or profit mutation occurs here.
 */
@Service
public class WmsReturnQcReviewService {
    private final JdbcTemplate jdbc;
    private final CustomerReturnIntakeService intakes;
    private final TransactionTemplate tx;

    public WmsReturnQcReviewService(JdbcTemplate jdbc,
        CustomerReturnIntakeService intakes,PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.intakes=intakes;
        this.tx=new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record Request(int qcQuantity,String qcCondition,
        String evidenceReference,String explanation) {}
    public record Decision(String reason) {}
    public record Review(String id,String observationId,String externalReceiptId,
        int externalQuantity,String externalCondition,int localQuantity,
        String localCondition,int qcQuantity,String qcCondition,
        String qcComparison,String evidenceReference,String explanation,
        String requestedBy,String requestedAt,String state,
        String decidedBy,String decidedAt,String decisionReason,
        boolean evidenceCurrent,boolean sourceActive,String operationalHold,
        boolean operatorIdentityVerified,boolean physicalReceiptVerified,
        boolean inventoryPosted,boolean cogsReversalEligible,
        String actualCogsReversal) {}

    private record Observation(UUID id,UUID warehouse,int quantity,
        String condition,UUID importedBy,String sourceState,String externalReceipt) {}
    private record CaseHead(UUID id,UUID observationId,UUID requestedBy) {}

    static void validate(Request request) {
        if(request==null||request.qcQuantity()<1||request.qcQuantity()>1000000
            ||!List.of("SELLABLE_CLAIMED","UNSELLABLE_CLAIMED","UNKNOWN")
                .contains(request.qcCondition()==null?"":request.qcCondition()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Positive QC quantity and explicit disposition required");
        ReturnReceiptEvidenceService.reference(request.evidenceReference());
        RefundRecoveryService.reason(request.explanation());
    }
    static String qcComparison(int expected,int qc,String expectedCondition,
        String qcCondition) {
        return "QC_"+WmsReturnReconciliationService
            .classify(expected,qc,expectedCondition,qcCondition);
    }
    private Observation observation(UUID tenant,UUID mp,UUID event,UUID intake,
        boolean lock) {
        var rows=jdbc.query("""
            SELECT v.id,v.warehouse_id,v.external_quantity,v.external_condition,
              v.recorded_by,s.state,v.external_receipt_id
            FROM finance_wms_return_observation_v99 v
            JOIN finance_wms_signing_source_v98 s
              ON s.tenant_id=v.tenant_id AND s.id=v.source_id
            WHERE v.tenant_id=? AND v.seller_marketplace_id=?
              AND v.event_id=? AND v.intake_id=?
            """+(lock?" FOR UPDATE OF s":""),
            (r,i)->new Observation(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getInt(3),r.getString(4),
                r.getObject(5,UUID.class),r.getString(6),r.getString(7)),
            tenant,mp,event,intake);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "A tenant-scoped V174 signed observation is required for QC triage");
        return rows.getFirst();
    }
    private CustomerReturnIntakeService.Intake requireCurrent(UUID tenant,UUID mp,
        UUID event,UUID intake,Observation signed) {
        if(!"APPROVED".equals(signed.sourceState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Signing key revoked; QC evidence cannot be acknowledged");
        var locked=jdbc.queryForList("""
            SELECT id FROM finance_customer_return_intake_v97
            WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
              AND id=? AND warehouse_id=? AND state='ATTESTED_LOCAL'
            FOR UPDATE
            """,UUID.class,tenant,mp,event,intake,signed.warehouse());
        if(locked.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Underlying parcel is no longer locally attested");
        var local=intakes.detail(tenant,mp,event,intake);
        if(!local.evidenceCurrent()||!"ATTESTED_LOCAL".equals(local.status())
            ||!signed.warehouse().toString().equals(local.warehouseId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "V63/V171/V172 source is stale; QC review held");
        return local;
    }
    private static String hold(CustomerReturnIntakeService.Intake local,
        String sourceState,String decision,String comparison,
        int signedQuantity,String signedCondition) {
        if(!local.evidenceCurrent()||!"ATTESTED_LOCAL".equals(local.status()))
            return "STALE_LOCAL_HOLD";
        if(!"APPROVED".equals(sourceState))return "REVOKED_SOURCE_HOLD";
        if("REJECTED".equals(decision))return "QC_REJECTED_HOLD";
        if(decision==null)return "QC_PENDING_HOLD";
        if(!"QC_MATCHED_UNVERIFIED".equals(comparison))
            return "QC_SIGNED_MISMATCH_HOLD";
        if(!"MATCHED_UNVERIFIED".equals(WmsReturnReconciliationService.classify(
            local.quantity(),signedQuantity,local.condition(),signedCondition)))
            return "LOCAL_SIGNED_MISMATCH_HOLD";
        return "OPERATOR_IDENTITY_UNVERIFIED_HOLD";
    }
    public Review report(UUID tenant,UUID mp,UUID event,UUID intake) {
        var local=intakes.detail(tenant,mp,event,intake);
        var rows=jdbc.query("""
            SELECT q.id,q.observation_id,v.external_receipt_id,
              v.external_quantity,v.external_condition,
              q.qc_quantity,q.qc_condition,q.evidence_reference,q.explanation,
              q.requested_by,q.requested_at,d.decision,d.decided_by,
              d.decided_at,d.reason,s.state
            FROM finance_wms_qc_case_v100 q
            JOIN finance_wms_return_observation_v99 v
              ON v.tenant_id=q.tenant_id AND v.id=q.observation_id
            JOIN finance_wms_signing_source_v98 s
              ON s.tenant_id=v.tenant_id AND s.id=v.source_id
            LEFT JOIN finance_wms_qc_decision_v100 d
              ON d.tenant_id=q.tenant_id AND d.case_id=q.id
            WHERE q.tenant_id=? AND q.seller_marketplace_id=?
              AND q.event_id=? AND q.intake_id=?
            """,(r,i)->display(r,local),tenant,mp,event,intake);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static String stamp(ResultSet r,String key)throws SQLException {
        OffsetDateTime date=r.getObject(key,OffsetDateTime.class);
        return date==null?null:date.toInstant().toString();
    }
    private static String uuid(ResultSet r,String key)throws SQLException {
        UUID value=r.getObject(key,UUID.class);
        return value==null?null:value.toString();
    }
    private static Review display(ResultSet r,CustomerReturnIntakeService.Intake local)
        throws SQLException {
        int signedQuantity=r.getInt("external_quantity");
        String signedCondition=r.getString("external_condition");
        String comparison=qcComparison(signedQuantity,r.getInt("qc_quantity"),
            signedCondition,r.getString("qc_condition"));
        String decision=r.getString("decision"),sourceState=r.getString("state");
        return new Review(uuid(r,"id"),uuid(r,"observation_id"),
            r.getString("external_receipt_id"),signedQuantity,signedCondition,
            local.quantity(),local.condition(),r.getInt("qc_quantity"),
            r.getString("qc_condition"),comparison,r.getString("evidence_reference"),
            r.getString("explanation"),uuid(r,"requested_by"),
            stamp(r,"requested_at"),decision==null?"PENDING":decision,
            uuid(r,"decided_by"),stamp(r,"decided_at"),r.getString("reason"),
            local.evidenceCurrent()&&"ATTESTED_LOCAL".equals(local.status()),
            "APPROVED".equals(sourceState),
            hold(local,sourceState,decision,comparison,signedQuantity,signedCondition),
            false,false,false,false,null);
    }
    public Review request(UUID tenant,UUID mp,UUID event,UUID intake,
        UUID actor,Request request) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        validate(request);
        try {
            tx.executeWithoutResult(status->{
                Observation signed=observation(tenant,mp,event,intake,true);
                requireCurrent(tenant,mp,event,intake,signed);
                RefundRecoveryService.requireIndependent(signed.importedBy(),actor);
                jdbc.update("""
                    INSERT INTO finance_wms_qc_case_v100
                     (id,tenant_id,seller_marketplace_id,event_id,intake_id,
                      warehouse_id,observation_id,qc_quantity,qc_condition,
                      evidence_reference,explanation,requested_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,mp,event,intake,signed.warehouse(),
                    signed.id(),request.qcQuantity(),request.qcCondition(),
                    request.evidenceReference(),request.explanation(),actor);
            });
        } catch(DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "QC case already filed or source/actor constraints violated");
        }
        return report(tenant,mp,event,intake);
    }
    public Review decide(UUID tenant,UUID mp,UUID event,UUID intake,
        UUID caseId,UUID actor,boolean acknowledge,Decision input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String reason=RefundRecoveryService.reason(input==null?null:input.reason());
        try {
            tx.executeWithoutResult(status->{
                var cases=jdbc.query("""
                    SELECT id,observation_id,requested_by
                    FROM finance_wms_qc_case_v100
                    WHERE tenant_id=? AND seller_marketplace_id=?
                      AND event_id=? AND intake_id=? AND id=?
                    FOR UPDATE
                    """,(r,i)->new CaseHead(r.getObject(1,UUID.class),
                        r.getObject(2,UUID.class),r.getObject(3,UUID.class)),
                    tenant,mp,event,intake,caseId);
                if(cases.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                CaseHead qc=cases.getFirst();
                RefundRecoveryService.requireIndependent(qc.requestedBy(),actor);
                Observation signed=observation(tenant,mp,event,intake,acknowledge);
                if(!qc.observationId().equals(signed.id()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "QC case signed observation identity changed");
                RefundRecoveryService.requireIndependent(signed.importedBy(),actor);
                if(acknowledge)requireCurrent(tenant,mp,event,intake,signed);
                jdbc.update("""
                    INSERT INTO finance_wms_qc_decision_v100
                      (id,tenant_id,case_id,decision,reason,decided_by)
                    VALUES (?,?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,caseId,
                    acknowledge?"ACKNOWLEDGED":"REJECTED",reason,actor);
            });
        } catch(DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "QC review already decided or independent review constraint violated");
        }
        return report(tenant,mp,event,intake);
    }
}
