package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
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
 * V173 cryptographically verified Ed25519 detached payloads against tenant-
 * enrolled public keys. A tenant can enroll its own key: neither signatures
 * nor two Owner approvals independently certify a real WMS or physical receipt.
 * Explicit NO STOCK, NO FBA/FBM, NO COGS.
 */
@Service
public class WmsSignedReturnService {
    private final JdbcTemplate jdbc;
    private final CustomerReturnIntakeService intakes;
    private final TransactionTemplate tx;
    public WmsSignedReturnService(JdbcTemplate jdbc,
        CustomerReturnIntakeService intakes,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.intakes=intakes;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record Register(UUID warehouseId,String sourceCode,
        String publicKeySpkiBase64,String reason) {}
    public record SignedInput(UUID sourceId,String externalReceiptId,
        OffsetDateTime observedAt,int quantity,String condition,
        String signatureBase64) {}
    public record Decision(String reason) {}
    public record Source(String id,String warehouseId,String sourceCode,String state,
        String requestedBy,String decidedBy,boolean operatorIdentityVerified) {}
    public record SignedReceipt(String id,String intakeId,String sourceId,
        String externalReceiptId,String observedAtUtc,int quantity,String condition,
        String canonicalSha256,boolean signatureValid,boolean operatorIdentityVerified,
        boolean physicalReceiptVerified,boolean inventoryPosted,
        boolean cogsReversalEligible,String actualCogsReversal) {}
    private record Key(UUID id,UUID warehouse,String code,String publicKey,
        String state,UUID requester,UUID approver) {}
    private record IntakeProof(UUID warehouse,String reference,int quantity,
        String condition,String state,boolean evidenceCurrent) {}
    static String code(String value) {
        if(value==null||!value.matches("[A-Z0-9][A-Z0-9_-]{7,79}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "WMS source code must be 8..80 uppercase alphanumeric characters");
        return value;
    }
    static byte[] decoded(String value,int maxBytes) {
        if(value==null||value.length()>256)throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"Invalid base64 proof");
        try {
            byte[] bytes=Base64.getDecoder().decode(value);
            if(bytes.length==0||bytes.length>maxBytes)throw new IllegalArgumentException();
            return bytes;
        } catch(IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid base64 proof");
        }
    }
    static PublicKey publicKey(String spki) {
        try {
            byte[] raw=decoded(spki,128);
            var key=KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(raw));
            if(!key.getAlgorithm().equalsIgnoreCase("EdDSA")
                &&!key.getAlgorithm().equalsIgnoreCase("Ed25519"))
                throw new IllegalArgumentException();
            return key;
        } catch(Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid DER X.509 Ed25519 SubjectPublicKeyInfo required");
        }
    }
    /**
     * V1 canonical UTF-8; each field is a validated UUID, number, ISO instant or
     * safe reference. \\n is a delimiter and cannot occur in the identifiers.
     * Never use a JSON serializer to assemble signed bytes.
     */
    static String canonical(UUID tenant,UUID mp,UUID event,UUID intake,UUID warehouse,
        UUID source,String externalReceipt,String localRef,int quantity,
        String condition,OffsetDateTime observed) {
        if(tenant==null||mp==null||event==null||intake==null||warehouse==null
            ||source==null||observed==null||quantity<1||quantity>1000000
            ||!List.of("SELLABLE_CLAIMED","UNSELLABLE_CLAIMED","UNKNOWN")
                 .contains(condition==null?"":condition))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Incomplete canonical WMS source binding");
        ReturnReceiptEvidenceService.reference(externalReceipt);
        ReturnReceiptEvidenceService.reference(localRef);
        return "MERCHIVRA_WMS_RETURN_V1\n"+tenant+"\n"+mp+"\n"+event+"\n"
            +intake+"\n"+warehouse+"\n"+source+"\n"+externalReceipt+"\n"
            +localRef+"\n"+quantity+"\n"+condition+"\n"
            +observed.toInstant()+"\n";
    }
    static boolean verify(String spki,String signature,String payload) {
        try {
            var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(spki));
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] raw=decoded(signature,64);
            if(raw.length!=64)return false;
            return verifier.verify(raw);
        } catch(ResponseStatusException e) {throw e;}
        catch(Exception exception) {return false;}
    }
    static void observed(OffsetDateTime value) {
        if(value==null||value.toInstant().isBefore(Instant.now().minusSeconds(366L*86400))
            ||value.toInstant().isAfter(Instant.now().plusSeconds(600)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "WMS observation must be dated in the last 366 days");
    }
    private Key key(UUID tenant,UUID source,boolean lock) {
        var values=jdbc.query("""
            SELECT id,warehouse_id,source_code,ed25519_spki_base64,
              state,requested_by,decided_by FROM finance_wms_signing_source_v98
            WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            (r,i)->new Key(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getString(3),r.getString(4),r.getString(5),
                r.getObject(6,UUID.class),r.getObject(7,UUID.class)),tenant,source);
        return values.stream().findFirst().orElseThrow(
            ()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Signing source not found in this tenant"));
    }
    private static Source source(Key key) {
        return new Source(key.id().toString(),key.warehouse().toString(),key.code(),
            key.state(),key.requester().toString(),
            key.approver()==null?null:key.approver().toString(),false);
    }
    private void audit(UUID tenant,UUID id,UUID actor,String action,String reason) {
        jdbc.update("""
            INSERT INTO finance_wms_source_audit_v98
              (id,tenant_id,source_id,actor_user_id,action,note)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,action,reason);
    }
    public Source register(UUID tenant,UUID actor,Register input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||input.warehouseId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Warehouse required");
        code(input.sourceCode());
        publicKey(input.publicKeySpkiBase64());
        String reason=RefundRecoveryService.reason(input.reason());
        UUID id=tx.execute(status->{
            Integer active=jdbc.queryForObject("""
                SELECT count(*) FROM erp_local_warehouse
                WHERE tenant_id=? AND id=? AND status='ACTIVE'
                """,Integer.class,tenant,input.warehouseId());
            if(active==null||active!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Active local warehouse not found");
            Integer exists=jdbc.queryForObject("""
                SELECT count(*) FROM finance_wms_signing_source_v98
                WHERE tenant_id=? AND warehouse_id=? AND source_code=?
                """,Integer.class,tenant,input.warehouseId(),input.sourceCode());
            if(exists!=null&&exists!=0)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"WMS source code already registered; use a fresh key version");
            UUID made=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finance_wms_signing_source_v98
                  (id,tenant_id,warehouse_id,source_code,ed25519_spki_base64,requested_by)
                VALUES (?,?,?,?,?,?)
                """,made,tenant,input.warehouseId(),input.sourceCode(),
                input.publicKeySpkiBase64(),actor);
            audit(tenant,made,actor,"REGISTERED",reason);
            return made;
        });
        return source(key(tenant,id,false));
    }
    public Source decide(UUID tenant,UUID actor,UUID sourceId,boolean approve,String note) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String reason=RefundRecoveryService.reason(note);
        tx.executeWithoutResult(status->{
            Key k=key(tenant,sourceId,true);
            RefundRecoveryService.requireIndependent(k.requester(),actor);
            if(approve&&!"PENDING".equals(k.state())
                ||!approve&&"REVOKED".equals(k.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signing source already decided or revoked");
            jdbc.update("""
                UPDATE finance_wms_signing_source_v98
                SET state=?,decided_by=?,decided_at=now(),decision_reason=?
                WHERE tenant_id=? AND id=?
                """,approve?"APPROVED":"REVOKED",actor,reason,tenant,sourceId);
            audit(tenant,sourceId,actor,approve?"APPROVED":"REVOKED",reason);
        });
        return source(key(tenant,sourceId,false));
    }
    public List<Source> sources(UUID tenant,UUID warehouse) {
        if(warehouse==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return jdbc.query("""
            SELECT id,warehouse_id,source_code,ed25519_spki_base64,state,
              requested_by,decided_by FROM finance_wms_signing_source_v98
            WHERE tenant_id=? AND warehouse_id=?
            ORDER BY requested_at DESC,id DESC LIMIT 100
            """,(r,i)->source(new Key(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getString(3),r.getString(4),
                r.getString(5),r.getObject(6,UUID.class),
                r.getObject(7,UUID.class))),tenant,warehouse);
    }
    private static IntakeProof from(CustomerReturnIntakeService.Intake intake) {
        return new IntakeProof(UUID.fromString(intake.warehouseId()),
            intake.sourceReference(),intake.quantity(),intake.condition(),
            intake.status(),intake.evidenceCurrent());
    }
    public SignedReceipt importReceipt(UUID tenant,UUID mp,UUID event,UUID intakeId,
        UUID actor,SignedInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||input.sourceId()==null||input.quantity()<1
            ||input.quantity()>1000000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Complete signed WMS receipt required");
        observed(input.observedAt());
        var intake=from(intakes.detail(tenant,mp,event,intakeId));
        if(!"ATTESTED_LOCAL".equals(intake.state())||!intake.evidenceCurrent()
            ||input.quantity()!=intake.quantity()||!intake.condition().equals(input.condition()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Current reviewed local return parcel and exact quantities required");
        Key before=key(tenant,input.sourceId(),false);
        if(!"APPROVED".equals(before.state())||!before.warehouse().equals(intake.warehouse()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Active independently reviewed WMS public key required");
        String payload=canonical(tenant,mp,event,intakeId,intake.warehouse(),
            input.sourceId(),input.externalReceiptId(),intake.reference(),
            input.quantity(),input.condition(),input.observedAt());
        if(!verify(before.publicKey(),input.signatureBase64(),payload))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Detached Ed25519 signature invalid for exact return parcel identity");
        UUID recorded;
        try {
            recorded=tx.execute(t->{
            // Lock source first, then parcel; serialize with key revocation,
            // and re-check exact tenant/order/warehouse/quantity identity.
            Key current=key(tenant,input.sourceId(),true);
            if(!"APPROVED".equals(current.state())
                ||!current.publicKey().equals(before.publicKey()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signing source was revoked or replaced");
            var rows=jdbc.queryForList("""
                SELECT id FROM finance_customer_return_intake_v97
                WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=?
                  AND id=? AND warehouse_id=? AND state='ATTESTED_LOCAL'
                FOR UPDATE
                """,UUID.class,tenant,mp,event,intakeId,intake.warehouse());
            if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Underlying manually attested parcel unavailable");
            var now=from(intakes.detail(tenant,mp,event,intakeId));
            if(!now.equals(intake))throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Underlying V63/V171/V172 evidence changed");
            Integer duplicate=jdbc.queryForObject("""
                SELECT count(*) FROM finance_wms_signed_return_v98
                WHERE tenant_id=? AND (intake_id=?
                  OR (source_id=? AND external_receipt_id=?))
                """,Integer.class,tenant,intakeId,input.sourceId(),input.externalReceiptId());
            if(duplicate!=null&&duplicate!=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate WMS receipt identity or already matched parcel");
            UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finance_wms_signed_return_v98
                  (id,tenant_id,warehouse_id,source_id,intake_id,
                   external_receipt_id,observed_at,quantity,condition_code,
                   signature_base64,canonical_sha256,recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,intake.warehouse(),input.sourceId(),intakeId,
                input.externalReceiptId(),input.observedAt(),input.quantity(),
                input.condition(),input.signatureBase64(),
                RefundRecoveryService.hash(payload),actor);
            return id;
            });
        } catch(DataIntegrityViolationException collision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "WMS receipt globally reserved by V173/V174 or scoped identity invalid");
        }
        return receipts(tenant,mp,event,intakeId).stream()
            .filter(x->x.id().equals(recorded.toString())).findFirst().orElseThrow();
    }
    public List<SignedReceipt> receipts(UUID tenant,UUID mp,UUID event,UUID intakeId) {
        // Verify request's tenant + marketplace + LIVE refund lineage.
        intakes.detail(tenant,mp,event,intakeId);
        return jdbc.query("""
            SELECT w.id,w.intake_id,w.source_id,w.external_receipt_id,
              w.observed_at,w.quantity,w.condition_code,w.canonical_sha256
            FROM finance_wms_signed_return_v98 w
            WHERE w.tenant_id=? AND w.intake_id=?
            ORDER BY w.created_at DESC,w.id DESC LIMIT 10
            """,(r,i)->new SignedReceipt(r.getObject(1,UUID.class).toString(),
                r.getObject(2,UUID.class).toString(),
                r.getObject(3,UUID.class).toString(),r.getString(4),
                r.getObject(5,OffsetDateTime.class).toInstant().toString(),
                r.getInt(6),r.getString(7),r.getString(8),
                true,false,false,false,false,null),tenant,intakeId);
    }
}
