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
 * V174: signed WMS OBSERVATIONS including quarantine for mismatch.
 * The tenant enrolled the key: no independent operator identity, actual
 * warehouse receipt, saleability, inventory or financial ledger recognition.
 */
@Service
public class WmsReturnReconciliationService {
    private final JdbcTemplate jdbc;
    private final CustomerReturnIntakeService intakes;
    private final TransactionTemplate tx;
    public WmsReturnReconciliationService(JdbcTemplate jdbc,
        CustomerReturnIntakeService intakes,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.intakes=intakes;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record ObservationInput(UUID sourceId,String externalReceiptId,
        OffsetDateTime observedAt,int externalQuantity,String externalCondition,
        String signatureBase64) {}
    public record Report(String intakeId,int localQuantity,String localCondition,
        String localReference,String reconciliation,String operationalHold,
        String externalReceiptId,Integer externalQuantity,String externalCondition,
        Integer quantityDelta,String sourceCode,boolean sourceActive,
        boolean evidenceCurrent,boolean signatureValid,
        boolean operatorIdentityVerified,boolean physicalReceiptVerified,
        boolean inventoryPosted,boolean cogsReversalEligible,
        String actualCogsReversal) {}
    private record Source(UUID id,UUID warehouse,String code,String spki,String state) {}
    private record Proof(String ref,int quantity,String condition,String sourceCode,
        String state,boolean sourceActive) {}
    private static final List<String> CONDITIONS=List.of(
        "SELLABLE_CLAIMED","UNSELLABLE_CLAIMED","UNKNOWN");
    static String classify(int local,int external,String lc,String ec) {
        boolean quantity=local!=external,condition=!lc.equals(ec);
        if(quantity&&condition)return "BOTH_MISMATCH";
        if(quantity)return "QUANTITY_MISMATCH";
        if(condition)return "CONDITION_MISMATCH";
        return "MATCHED_UNVERIFIED";
    }
    static String canonical(UUID tenant,UUID mp,UUID event,UUID intake,UUID warehouse,
        UUID source,String externalReceipt,String localRef,int localQuantity,
        String localCondition,int externalQuantity,String externalCondition,
        OffsetDateTime observed) {
        if(tenant==null||mp==null||event==null||intake==null||warehouse==null
            ||source==null||observed==null||localQuantity<1||localQuantity>1000000
            ||externalQuantity<1||externalQuantity>1000000
            ||!CONDITIONS.contains(localCondition)||!CONDITIONS.contains(externalCondition))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Incomplete V174 signed observation identity");
        ReturnReceiptEvidenceService.reference(externalReceipt);
        ReturnReceiptEvidenceService.reference(localRef);
        return "MERCHIVRA_WMS_OBSERVATION_V2\n"+tenant+"\n"+mp+"\n"
            +event+"\n"+intake+"\n"+warehouse+"\n"+source+"\n"
            +externalReceipt+"\n"+localRef+"\n"+localQuantity+"\n"
            +localCondition+"\n"+externalQuantity+"\n"+externalCondition+"\n"
            +observed.toInstant()+"\n";
    }
    private Source source(UUID tenant,UUID source,boolean lock) {
        var found=jdbc.query("""
            SELECT id,warehouse_id,source_code,ed25519_spki_base64,state
            FROM finance_wms_signing_source_v98 WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            (r,i)->new Source(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getString(3),r.getString(4),
                r.getString(5)),tenant,source);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Tenant signing source not found");
        return found.getFirst();
    }
    private static void eligible(CustomerReturnIntakeService.Intake local) {
        if(!"ATTESTED_LOCAL".equals(local.status())||!local.evidenceCurrent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Current independently reviewed V172 parcel is required");
    }
    private static Report display(CustomerReturnIntakeService.Intake local,Proof proof) {
        boolean current=local.evidenceCurrent()&&"ATTESTED_LOCAL".equals(local.status());
        String reason=proof==null?"PENDING_EXTERNAL_EVIDENCE":
            !current?"STALE_LOCAL_HOLD":
            !proof.sourceActive()?"REVOKED_SOURCE_HOLD":
            !proof.state().equals("MATCHED_UNVERIFIED")?"MISMATCH_QUARANTINE_HOLD":
            "IDENTITY_UNVERIFIED_HOLD";
        return new Report(local.id(),local.quantity(),local.condition(),
            local.sourceReference(),proof==null?"NO_EVIDENCE":proof.state(),
            reason,proof==null?null:proof.ref(),
            proof==null?null:proof.quantity(),
            proof==null?null:proof.condition(),
            proof==null?null:proof.quantity()-local.quantity(),
            proof==null?null:proof.sourceCode(),
            proof!=null&&proof.sourceActive(),current,proof!=null,
            false,false,false,false,null);
    }
    private static Proof proof(ResultSet r,int i)throws SQLException {
        return new Proof(r.getString(1),r.getInt(2),r.getString(3),
            r.getString(4),r.getString(5),"APPROVED".equals(r.getString(6)));
    }
    public Report report(UUID tenant,UUID mp,UUID event,UUID intakeId) {
        var local=intakes.detail(tenant,mp,event,intakeId);
        // V99 is preferred. Unique registry ensures one V173 OR V174 per
        // intake. Historical V173 records stay auditable after revocation.
        var observations=jdbc.query("""
            SELECT v.external_receipt_id,v.external_quantity,v.external_condition,
             s.source_code,v.reconciliation,s.state
            FROM finance_wms_return_observation_v99 v
            JOIN finance_wms_signing_source_v98 s
              ON s.tenant_id=v.tenant_id AND s.id=v.source_id
            WHERE v.tenant_id=? AND v.seller_marketplace_id=?
              AND v.event_id=? AND v.intake_id=?
            ORDER BY v.created_at DESC,v.id DESC LIMIT 1
            """,WmsReturnReconciliationService::proof,tenant,mp,event,intakeId);
        if(!observations.isEmpty())return display(local,observations.getFirst());
        var old=jdbc.query("""
            SELECT v.external_receipt_id,v.quantity,v.condition_code,
             s.source_code,'MATCHED_UNVERIFIED',s.state
            FROM finance_wms_signed_return_v98 v
            JOIN finance_wms_signing_source_v98 s
              ON s.tenant_id=v.tenant_id AND s.id=v.source_id
            WHERE v.tenant_id=? AND v.intake_id=?
            ORDER BY v.created_at DESC,v.id DESC LIMIT 1
            """,WmsReturnReconciliationService::proof,tenant,intakeId);
        return display(local,old.isEmpty()?null:old.getFirst());
    }
    public Report record(UUID tenant,UUID mp,UUID event,UUID intakeId,UUID actor,
        ObservationInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||input.sourceId()==null||input.externalQuantity()<1
            ||input.externalQuantity()>1000000
            ||!CONDITIONS.contains(input.externalCondition()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Positive source quantity and explicit disposition are required");
        WmsSignedReturnService.observed(input.observedAt());
        var local=intakes.detail(tenant,mp,event,intakeId);
        eligible(local);
        UUID warehouse=UUID.fromString(local.warehouseId());
        Source before=source(tenant,input.sourceId(),false);
        if(!warehouse.equals(before.warehouse())||!"APPROVED".equals(before.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An independently approved active key for this local warehouse is required");
        String canonical=canonical(tenant,mp,event,intakeId,warehouse,
            input.sourceId(),input.externalReceiptId(),local.sourceReference(),
            local.quantity(),local.condition(),input.externalQuantity(),
            input.externalCondition(),input.observedAt());
        if(!WmsSignedReturnService.verify(before.spki(),input.signatureBase64(),canonical))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ed25519 observation signature does not bind all original and observed fields");
        String reconciliation=classify(local.quantity(),input.externalQuantity(),
            local.condition(),input.externalCondition());
        try {
            tx.executeWithoutResult(t->{
                Source current=source(tenant,input.sourceId(),true);
                if(!"APPROVED".equals(current.state())
                    ||!current.spki().equals(before.spki()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "WMS signing key revoked");
                // A key rotation cannot bypass global source-receipt dedup:
                // V99 registry reserves both V173 + V174 references.
                var locks=jdbc.queryForList("""
                    SELECT id FROM finance_customer_return_intake_v97
                    WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
                      AND id=? AND warehouse_id=? AND state='ATTESTED_LOCAL'
                    FOR UPDATE
                    """,UUID.class,tenant,mp,event,intakeId,warehouse);
                if(locks.size()!=1)throw new ResponseStatusException(
                    HttpStatus.CONFLICT,"Current parcel not found");
                var refreshed=intakes.detail(tenant,mp,event,intakeId);
                eligible(refreshed);
                if(!refreshed.equals(local))throw new ResponseStatusException(
                    HttpStatus.CONFLICT,"V63/V171/V172 source identity changed");
                Integer duplicate=jdbc.queryForObject("""
                    SELECT count(*) FROM finance_wms_receipt_identity_v99
                    WHERE tenant_id=? AND (intake_id=?
                      OR (warehouse_id=? AND external_receipt_id=?))
                    """,Integer.class,tenant,intakeId,warehouse,input.externalReceiptId());
                if(duplicate!=null&&duplicate!=0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "WMS receipt already reserved by V173 or V174");
                jdbc.update("""
                    INSERT INTO finance_wms_return_observation_v99
                      (id,tenant_id,seller_marketplace_id,event_id,
                       warehouse_id,source_id,intake_id,external_receipt_id,
                       observed_at,external_quantity,external_condition,
                       local_quantity,local_condition,reconciliation,
                       signature_base64,canonical_sha256,recorded_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,mp,event,warehouse,input.sourceId(),
                    intakeId,input.externalReceiptId(),input.observedAt(),
                    input.externalQuantity(),input.externalCondition(),
                    local.quantity(),local.condition(),reconciliation,
                    input.signatureBase64(),RefundRecoveryService.hash(canonical),actor);
            });
        } catch(DataIntegrityViolationException collision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "WMS receipt/source already reserved or scoped identity invalid");
        }
        return report(tenant,mp,event,intakeId);
    }
}
