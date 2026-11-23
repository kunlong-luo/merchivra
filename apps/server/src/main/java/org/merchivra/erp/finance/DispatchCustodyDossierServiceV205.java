package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V205: a custody EVIDENCE-READINESS dossier for a V204 signed local outbound.
 *
 * The operator registry and carrier manifest references are tenant assertions:
 * not authenticated external records or a real carrier handoff. An independent
 * Owner can review document consistency, but NO API promotes a dossier to
 * verified external operator identity, Amazon fulfillment, inventory or COGS.
 */
@Service
public class DispatchCustodyDossierServiceV205 {
    public record Submit(String operatorRegistryRef,String operatorDocumentSha256,
        String carrierHandoffRef,String carrierDocumentSha256,
        OffsetDateTime claimedHandoffAt) {}
    public record ReviewInput(String decision,String reason) {}
    public record Dossier(String dossierId,String signedDispatchId,
        String claimId,String sourceId,String operatorRegistryRef,
        String operatorDocumentSha256,String carrierHandoffRef,
        String carrierDocumentSha256,int assignedQuantity,String integrityState,
        String internalReviewDecision,String submittedBy,String reviewedBy,
        boolean operatorIdentityVerified,boolean physicalHandoffVerified,
        boolean platformFulfillmentVerified,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}
    public record Preview(String orderId,String marketplaceId,String scope,
        int signedDispatchCount,int missingDossierCount,List<Dossier> dossiers,
        List<String> blockerCodes,boolean operatorIdentityVerified,
        boolean physicalHandoffVerified,boolean platformFulfillmentVerified,
        boolean actualBatchCogsVerified,String realizedBatchCogs) {}
    private record Raw(UUID id,UUID signedDispatch,UUID claim,UUID source,
        String operatorRef,String operatorDigest,String carrierRef,String carrierDigest,
        int quantity,String dbState,String decision,UUID submittedBy,UUID reviewedBy) {}
    private record Actor(UUID submitter,UUID importer,UUID registrar) {}

    private static final String DOSSIERS_SQL = """
        SELECT dossier_id,signed_dispatch_id,snapshot_claim_id,snapshot_source_id,
          operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
          carrier_document_sha256,snapshot_quantity,integrity_state,decision,
          submitted_by,reviewed_by
        FROM finance_dispatch_custody_integrity_v108
        WHERE tenant_id=? AND marketplace_id=? AND sales_order_id=?
        ORDER BY dossier_id LIMIT 1001
        """;
    private final JdbcTemplate jdbc;
    private final SaleLotSignedDispatchServiceV204 signed;

    public DispatchCustodyDossierServiceV205(JdbcTemplate jdbc,
        SaleLotSignedDispatchServiceV204 signed) {
        this.jdbc=jdbc;this.signed=signed;
    }

    static String reference(String ref) {
        if(ref==null || !ref.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "External operator or carrier reference must be 8-160 safe characters");
        return ref;
    }
    static String declaredDigest(String digest) {
        if(digest==null||!digest.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Lowercase 64-character claimed document SHA-256 required");
        return digest;
    }
    static String decision(String value) {
        if(!List.of("DOCS_CONSISTENT_UNVERIFIED","NEEDS_EVIDENCE","REJECTED")
            .contains(value==null?"":value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only internal unverified document-consistency decisions are allowed");
        return value;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Dossier submit(UUID tenant,UUID mp,UUID order,UUID signedDispatchId,
        UUID actor,Submit input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(signedDispatchId==null||input==null||input.claimedHandoffAt()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Complete operator and carrier document references required");
        reference(input.operatorRegistryRef());
        reference(input.carrierHandoffRef());
        declaredDigest(input.operatorDocumentSha256());
        declaredDigest(input.carrierDocumentSha256());
        WmsSignedReturnService.observed(input.claimedHandoffAt());
        if(input.operatorDocumentSha256().equals(input.carrierDocumentSha256()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Operator registry and carrier handoff must reference different documents");
        // V204 independently recomputes registered-key signature and local V203
        // sale/SKU/QC/quantity state before any V108 write.
        var baseline=signed.preview(tenant,mp,order);
        boolean current=baseline.evidence().stream().anyMatch(
            x->x.id().equals(signedDispatchId.toString())
                &&"TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED".equals(x.evidenceState()));
        if(!current)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "No current independently recomputed V204 signed dispatch");
        UUID made=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_dispatch_custody_dossier_v108
                  (id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
                   operator_registry_ref,operator_document_sha256,
                   carrier_handoff_ref,carrier_document_sha256,claimed_handoff_at,
                   submitted_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,made,tenant,mp,order,signedDispatchId,
                input.operatorRegistryRef(),input.operatorDocumentSha256(),
                input.carrierHandoffRef(),input.carrierDocumentSha256(),
                input.claimedHandoffAt(),actor);
        } catch(DataIntegrityViolationException violation) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stale, conflicting or already attributed custody dossier");
        }
        return preview(tenant,mp,order).dossiers().stream()
            .filter(x->x.dossierId().equals(made.toString())).findFirst().orElseThrow();
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Dossier review(UUID tenant,UUID mp,UUID order,UUID dossierId,
        UUID actor,ReviewInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(dossierId==null||input==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Custody dossier and independent internal reviewer required");
        String choice=decision(input.decision());
        String reason=RefundRecoveryService.reason(input.reason());
        var people=jdbc.query("""
            SELECT c.submitted_by,d.recorded_by,s.requested_by
            FROM finance_dispatch_custody_dossier_v108 c
            JOIN finance_sale_lot_signed_dispatch_v107 d
              ON d.tenant_id=c.tenant_id AND d.id=c.signed_dispatch_id
            JOIN finance_wms_signing_source_v98 s
              ON s.tenant_id=d.tenant_id AND s.id=d.source_id
            WHERE c.tenant_id=? AND c.marketplace_id=?
              AND c.sales_order_id=? AND c.id=?
            FOR UPDATE OF c
            """,(r,i)->new Actor(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getObject(3,UUID.class)),
            tenant,mp,order,dossierId);
        if(people.size()!=1)throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Custody dossier not found in tenant/order");
        Actor people0=people.getFirst();
        RefundRecoveryService.requireIndependent(people0.submitter(),actor);
        RefundRecoveryService.requireIndependent(people0.importer(),actor);
        RefundRecoveryService.requireIndependent(people0.registrar(),actor);
        if("DOCS_CONSISTENT_UNVERIFIED".equals(choice)) {
            var existing=preview(tenant,mp,order).dossiers().stream()
                .filter(x->x.dossierId().equals(dossierId.toString()))
                .findFirst().orElseThrow();
            if(!"EXTERNAL_EVIDENCE_UNREVIEWED".equals(existing.integrityState()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot record consistent review for stale or disputed evidence");
        }
        UUID made=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_dispatch_custody_review_v108
                  (id,tenant_id,dossier_id,reviewed_by,decision,reason)
                VALUES (?,?,?,?,?,?)
                """,made,tenant,dossierId,actor,choice,reason);
        } catch(DataIntegrityViolationException violation) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Review already exists or independent actor check rejected");
        }
        return preview(tenant,mp,order).dossiers().stream()
            .filter(x->x.dossierId().equals(dossierId.toString())).findFirst().orElseThrow();
    }

    static String effectiveState(String dbState,
        SaleLotSignedDispatchServiceV204.SignedObservation signedRow) {
        if(signedRow==null
            || !"TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED".equals(signedRow.evidenceState())
            || !signedRow.signatureValidForRegisteredKey())
            return "SIGNED_DISPATCH_RECOMPUTATION_FAILED_OR_STALE";
        return dbState;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID mp,UUID order) {
        var base=signed.preview(tenant,mp,order);
        Map<String,SaleLotSignedDispatchServiceV204.SignedObservation> bySigned=new HashMap<>();
        for(var row:base.evidence()) {
            if(bySigned.putIfAbsent(row.id(),row)!=null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate signed dispatch in tenant/order");
        }
        var raw=jdbc.query(DOSSIERS_SQL,(r,i)->new Raw(
                r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getObject(4,UUID.class),
                r.getString(5),r.getString(6),r.getString(7),r.getString(8),
                r.getInt(9),r.getString(10),r.getString(11),
                r.getObject(12,UUID.class),r.getObject(13,UUID.class)),
            tenant,mp,order);
        if(raw.size()>1000)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Unbounded external custody dossiers");
        var blockers=new ArrayList<String>();
        blockers.add("EXTERNAL_OPERATOR_REGISTRY_SOURCE_NOT_INDEPENDENTLY_AUTHENTICATED");
        blockers.add("CARRIER_HANDOFF_OR_PHYSICAL_CUSTODY_NOT_INDEPENDENTLY_VERIFIED");
        blockers.add("AMAZON_FBA_FBM_FULFILLMENT_NOT_VERIFIED");
        blockers.add("G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED");
        var rows=new ArrayList<Dossier>();
        boolean disputed=false;
        for(var r:raw) {
            String state=effectiveState(r.dbState(),
                bySigned.get(r.signedDispatch().toString()));
            if(!"INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED".equals(state))
                disputed=true;
            rows.add(new Dossier(r.id().toString(),r.signedDispatch().toString(),
                r.claim().toString(),r.source().toString(),r.operatorRef(),
                r.operatorDigest(),r.carrierRef(),r.carrierDigest(),r.quantity(),
                state,r.decision(),r.submittedBy().toString(),
                r.reviewedBy()==null?null:r.reviewedBy().toString(),
                false,false,false,false,null));
        }
        int missing=Math.max(0,bySigned.size()-raw.size());
        if(missing>0)blockers.add("SIGNED_DISPATCH_WITHOUT_CUSTODY_DOSSIER");
        if(disputed)blockers.add("UNREVIEWED_STALE_OR_CONFLICTING_CUSTODY_EVIDENCE");
        if(!base.blockerCodes().isEmpty()
            &&base.blockerCodes().contains("WMS_DISPATCH_SOURCE_STALE_OR_INVALID"))
            blockers.add("SIGNED_LOCAL_DISPATCH_STALE");
        return new Preview(order.toString(),mp.toString(),
            "TENANT_DECLARED_EXTERNAL_CUSTODY_ONLY",bySigned.size(),missing,
            List.copyOf(rows),List.copyOf(blockers),false,false,false,false,null);
    }
}
