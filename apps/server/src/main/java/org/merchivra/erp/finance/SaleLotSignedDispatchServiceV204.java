package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V204: a real Ed25519 verification of a dispatch observation bound to a
 * V203 local claim. The public key is registered BY THE TENANT. Therefore even
 * a valid signature and V101 key-possession proof NEVER validate Amazon
 * fulfillment, the third-party operator's identity, physical dispatch or COGS.
 */
@Service
public class SaleLotSignedDispatchServiceV204 {
    public record SignedInput(UUID sourceId,int quantity,
        OffsetDateTime observedAt,String signatureBase64) {}
    public record SignedObservation(String id,String claimId,String sourceId,
        String externalDispatchRef,int quantity,String observedAtUtc,
        String evidenceState,boolean signatureValidForRegisteredKey,
        boolean operatorIdentityVerified,boolean physicalDispatchVerified,
        boolean platformFulfillmentVerified,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}
    public record Preview(String orderId,String marketplaceId,String scope,
        int localClaimCount,int missingSignedDispatchCount,
        List<SignedObservation> evidence,List<String> blockerCodes,
        boolean platformFulfillmentVerified,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}
    private record Claim(UUID id,UUID marketplace,UUID order,UUID item,
        UUID pickLine,UUID receipt,UUID warehouse,int quantity,
        String dispatchRef,String externalItem,String payloadSha) {}
    private record Source(UUID id,UUID warehouse,String state,String spki) {}
    private record Row(UUID id,UUID claimId,UUID sourceId,
        UUID item,UUID pickLine,UUID receipt,UUID warehouse,
        String dispatchRef,String externalItem,String payloadSha,
        int quantity,OffsetDateTime observedAt,String signature,String sha,
        String sourcePublicKey,String dbState) {}

    private final JdbcTemplate jdbc;
    private final OrderLotAssignmentIntegrityServiceV203 local;
    public SaleLotSignedDispatchServiceV204(JdbcTemplate jdbc,
        OrderLotAssignmentIntegrityServiceV203 local) {
        this.jdbc=jdbc;this.local=local;
    }
    /** Exactly nine UUID fields and five line-delimited scalar fields.
     * Never deserialize a JSON map and reserialize it to compute signed bytes.
     */
    static String canonical(UUID tenant,UUID marketplace,UUID order,
        UUID claim,UUID item,UUID pickLine,UUID receipt,UUID warehouse,
        UUID source,String externalDispatch,String externalItem,
        String externalPayloadSha,int quantity,OffsetDateTime observedAt) {
        if(tenant==null||marketplace==null||order==null||claim==null
            ||item==null||pickLine==null||receipt==null||warehouse==null
            ||source==null||observedAt==null||quantity<=0||quantity>1000000
            ||externalDispatch==null
            ||!externalDispatch.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}")
            ||externalItem==null||!externalItem.matches("[A-Za-z0-9._:/-]{1,160}")
            ||externalPayloadSha==null
            ||!externalPayloadSha.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot canonicalize untrusted dispatch or sale item identity");
        return "MERCHIVRA_WMS_SALE_DISPATCH_V1\n"
            +tenant+"\n"+marketplace+"\n"+order+"\n"+claim+"\n"
            +item+"\n"+pickLine+"\n"+receipt+"\n"+warehouse+"\n"
            +source+"\n"+externalDispatch+"\n"+externalItem+"\n"
            +externalPayloadSha+"\n"+quantity+"\n"+observedAt.toInstant()+"\n";
    }

    private Claim claim(UUID tenant,UUID mp,UUID order,UUID claimId) {
        var claims=jdbc.query("""
            SELECT id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
              receipt_id,snapshot_warehouse_id,assigned_quantity,
              external_dispatch_ref,snapshot_external_item_id,external_payload_sha256
            FROM finance_sale_lot_claim_v106
            WHERE tenant_id=? AND marketplace_id=? AND sales_order_id=? AND id=?
            FOR UPDATE
            """,(r,i)->new Claim(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getObject(4,UUID.class),r.getObject(5,UUID.class),
                r.getObject(6,UUID.class),r.getObject(7,UUID.class),
                r.getInt(8),r.getString(9),r.getString(10),r.getString(11)),
            tenant,mp,order,claimId);
        if(claims.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Selected current local sale/lot claim unavailable");
        return claims.getFirst();
    }
    private Source source(UUID tenant,UUID sourceId) {
        var keys=jdbc.query("""
            SELECT id,warehouse_id,state,ed25519_spki_base64
            FROM finance_wms_signing_source_v98
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(r,i)->new Source(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getString(3),r.getString(4)),
            tenant,sourceId);
        if(keys.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Tenant-scoped signing source not found");
        return keys.getFirst();
    }
    private static boolean intact(
        OrderLotAssignmentIntegrityServiceV203.Preview p,UUID claimId) {
        return "COGS_READY_PREVIEW".equals(p.orderCostReadiness())
            &&p.claims().stream().anyMatch(c->c.claimId().equals(claimId.toString())
                &&"LOCAL_LINK_INTACT_UNVERIFIED".equals(c.integrityState()));
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public SignedObservation importSigned(UUID tenant,UUID mp,UUID order,
        UUID claimId,UUID actor,SignedInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||input.sourceId()==null||input.observedAt()==null
            ||input.quantity()<1||input.quantity()>1000000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Complete signed dispatch observation required");
        WmsSignedReturnService.observed(input.observedAt());
        // Align existing V98/V101 lock sequence (source, then local claim).
        Source key=source(tenant,input.sourceId());
        Claim c=claim(tenant,mp,order,claimId);
        if(!"APPROVED".equals(key.state())
            ||!key.warehouse().equals(c.warehouse())
            ||input.quantity()!=c.quantity())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Signed dispatch source/warehouse/quantity inconsistent");
        var current=local.preview(tenant,mp,order);
        if(!intact(current,claimId))throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Source claim or historical order SKU is stale");
        Integer keyProofs=jdbc.queryForObject("""
            SELECT count(*) FROM finance_wms_key_proof_v101
            WHERE tenant_id=? AND source_id=? AND warehouse_id=?
              AND result='KEY_POSSESSION_ONLY'
            """,Integer.class,tenant,key.id(),key.warehouse());
        if(keyProofs==null||keyProofs==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Prior independent key possession proof required");
        String canonical=canonical(tenant,mp,order,c.id(),c.item(),
            c.pickLine(),c.receipt(),c.warehouse(),key.id(),c.dispatchRef(),
            c.externalItem(),c.payloadSha(),input.quantity(),input.observedAt());
        if(!WmsSignedReturnService.verify(key.spki(),input.signatureBase64(),canonical))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ed25519 dispatch signature invalid for exact local claim");
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_sale_lot_signed_dispatch_v107
                  (id,tenant_id,marketplace_id,sales_order_id,claim_id,source_id,
                   warehouse_id,external_dispatch_ref,quantity,observed_at,
                   signature_base64,canonical_sha256,recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,mp,order,c.id(),key.id(),c.warehouse(),
                c.dispatchRef(),input.quantity(),input.observedAt(),
                input.signatureBase64(),RefundRecoveryService.hash(canonical),actor);
        } catch(DataIntegrityViolationException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate signed dispatch or stale source binding");
        }
        return preview(tenant,mp,order).evidence().stream()
            .filter(e->e.id().equals(id.toString())).findFirst().orElseThrow();
    }

    private static String verifiedState(Row r,UUID tenant,UUID mp,UUID order,
        Map<String,OrderLotAssignmentIntegrityServiceV203.Claim> localClaims) {
        try {
            String payload=canonical(tenant,mp,order,r.claimId(),r.item(),
                r.pickLine(),r.receipt(),r.warehouse(),r.sourceId(),
                r.dispatchRef(),r.externalItem(),r.payloadSha(),
                r.quantity(),r.observedAt());
            if(!Objects.equals(RefundRecoveryService.hash(payload),r.sha())
                ||!WmsSignedReturnService.verify(r.sourcePublicKey(),
                    r.signature(),payload))
                return "SIGNED_PAYLOAD_RECOMPUTATION_FAILED";
        } catch(RuntimeException invalidPayload) {
            return "SIGNED_PAYLOAD_RECOMPUTATION_FAILED";
        }
        var linked=localClaims.get(r.claimId().toString());
        if(linked==null||!"LOCAL_LINK_INTACT_UNVERIFIED".equals(linked.integrityState()))
            return "SALE_LOT_OR_HISTORICAL_SKU_STALE";
        return r.dbState();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID mp,UUID order) {
        // Reuse V203 full-order, tenant-scoped and historical SKU validation.
        var baseline=local.preview(tenant,mp,order);
        Map<String,OrderLotAssignmentIntegrityServiceV203.Claim> claims=new HashMap<>();
        for(var c:baseline.claims())claims.put(c.claimId(),c);
        var rows=jdbc.query("""
            SELECT d.id,d.claim_id,d.source_id,d.order_item_id,d.pick_line_id,
              d.receipt_id,d.warehouse_id,d.external_dispatch_ref,
              d.snapshot_external_item_id,d.external_payload_sha256,
              d.quantity,d.observed_at,d.signature_base64,d.canonical_sha256,
              d.ed25519_spki_base64,d.evidence_state
            FROM finance_sale_lot_dispatch_integrity_v107 d
            WHERE d.tenant_id=? AND d.marketplace_id=? AND d.sales_order_id=?
            ORDER BY d.id LIMIT 1001
            """,(r,i)->new Row(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getObject(4,UUID.class),r.getObject(5,UUID.class),
                r.getObject(6,UUID.class),r.getObject(7,UUID.class),
                r.getString(8),r.getString(9),r.getString(10),
                r.getInt(11),r.getObject(12,OffsetDateTime.class),
                r.getString(13),r.getString(14),r.getString(15),
                r.getString(16)),tenant,mp,order);
        if(rows.size()>1000)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Unbounded signed dispatch observation count");
        var observations=new ArrayList<SignedObservation>();
        var blockers=new ArrayList<String>();
        blockers.add("TENANT_ENROLLED_WMS_KEY_NOT_EXTERNAL_OPERATOR_ATTESTATION");
        blockers.add("AMAZON_FBA_FBM_FULFILLMENT_EVIDENCE_UNAVAILABLE");
        blockers.add("G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED");
        int missing=Math.max(0,claims.size()-rows.size());
        if(missing>0)blockers.add("LOCAL_CLAIMS_WITHOUT_SIGNED_DISPATCH_OBSERVATION");
        if(!"COGS_READY_PREVIEW".equals(baseline.orderCostReadiness()))
            blockers.add("ORDER_HISTORICAL_SKU_COST_NOT_READY");
        for(var r:rows) {
            String state=verifiedState(r,tenant,mp,order,claims);
            if(!"TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED".equals(state)
                &&!blockers.contains("WMS_DISPATCH_SOURCE_STALE_OR_INVALID"))
                blockers.add("WMS_DISPATCH_SOURCE_STALE_OR_INVALID");
            observations.add(new SignedObservation(r.id().toString(),
                r.claimId().toString(),r.sourceId().toString(),r.dispatchRef(),
                r.quantity(),r.observedAt().toInstant().toString(),state,
                !state.equals("SIGNED_PAYLOAD_RECOMPUTATION_FAILED"),
                false,false,false,false,null));
        }
        return new Preview(order.toString(),mp.toString(),
            "SIGNED_LOCAL_DISPATCH_UNVERIFIED_EXTERNAL",claims.size(),missing,
            List.copyOf(observations),List.copyOf(blockers),false,false,null);
    }
}
