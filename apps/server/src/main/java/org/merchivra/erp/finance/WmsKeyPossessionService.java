package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
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
 * V175-B. Only proves possession of a private key corresponding to a tenant-
 * enrolled WMS public key. Never certifies warehouse operator, parcel, stock,
 * cost basis or profit. Issuance and proof are append-only; source revocation
 * dynamically withdraws readiness without erasing prior audit evidence.
 */
@Service
public class WmsKeyPossessionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecureRandom random=new SecureRandom();
    public WmsKeyPossessionService(JdbcTemplate jdbc,
        PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.tx=new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record ProofInput(String signatureBase64) {}
    public record Challenge(String id,String sourceId,String warehouseId,
        String nonceHex,String canonicalPayload,String payloadSha256,
        String expiresAtUtc,String issuedBy,String verifiedBy,String verifiedAtUtc,
        String status,boolean sourceActive,boolean signatureValid,
        boolean operatorIdentityVerified,boolean physicalReceiptVerified,
        boolean inventoryPosted,boolean cogsReversalEligible,
        String actualCogsReversal) {}
    private record Source(UUID id,UUID warehouse,String spki,String state,
        UUID registrar) {}
    private record Row(UUID id,UUID source,UUID warehouse,String nonce,
        String digest,Instant expiry,UUID issuer,UUID reviewer,Instant verifiedAt) {}

    static String canonical(UUID tenant,UUID source,UUID warehouse,UUID challenge,
        String nonce,Instant expiry) {
        if(tenant==null||source==null||warehouse==null||challenge==null
            ||nonce==null||!nonce.matches("[0-9a-f]{64}")||expiry==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact key possession challenge scope required");
        return "MERCHIVRA_WMS_KEY_POSSESSION_V1\n"+
            tenant+"\n"+warehouse+"\n"+source+"\n"+challenge+"\n"+
            nonce+"\n"+expiry+"\n";
    }
    private Source source(UUID tenant,UUID sourceId,boolean lock) {
        var rows=jdbc.query("""
            SELECT id,warehouse_id,ed25519_spki_base64,state,requested_by
            FROM finance_wms_signing_source_v98
            WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            (r,i)->new Source(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getString(3),r.getString(4),
                r.getObject(5,UUID.class)),tenant,sourceId);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "WMS signing source not in this tenant");
        return rows.getFirst();
    }
    public List<Challenge> list(UUID tenant,UUID sourceId) {
        Source source=source(tenant,sourceId,false);
        return jdbc.query("""
            SELECT c.id,c.source_id,c.warehouse_id,c.nonce_hex,
               c.canonical_sha256,c.expires_at,c.issued_by,
               p.verified_by,p.verified_at
            FROM finance_wms_key_challenge_v101 c
            LEFT JOIN finance_wms_key_proof_v101 p
              ON p.tenant_id=c.tenant_id AND p.challenge_id=c.id
            WHERE c.tenant_id=? AND c.source_id=?
            ORDER BY c.issued_at DESC,c.id DESC LIMIT 10
            """,(r,i)->{
                var reviewer=r.getObject(8,UUID.class);
                var at=r.getObject(9,OffsetDateTime.class);
                return view(tenant,source,new Row(r.getObject(1,UUID.class),
                    r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                    r.getString(4),r.getString(5),
                    r.getObject(6,OffsetDateTime.class).toInstant(),
                    r.getObject(7,UUID.class),reviewer,
                    at==null?null:at.toInstant()));
            },tenant,sourceId);
    }
    private static Challenge view(UUID tenant,Source source,Row row) {
        boolean approved="APPROVED".equals(source.state());
        String status=!approved?"SOURCE_REVOKED_HOLD":
            row.reviewer()!=null?"KEY_POSSESSION_ONLY":
            !Instant.now().isBefore(row.expiry())?"EXPIRED":"AWAITING_SIGNATURE";
        return new Challenge(row.id().toString(),row.source().toString(),
            row.warehouse().toString(),row.nonce(),
            canonical(tenant,row.source(),row.warehouse(),row.id(),
                row.nonce(),row.expiry()),row.digest(),row.expiry().toString(),
            row.issuer().toString(),
            row.reviewer()==null?null:row.reviewer().toString(),
            row.verifiedAt()==null?null:row.verifiedAt().toString(),status,
            approved,row.reviewer()!=null,false,false,false,false,null);
    }
    public Challenge issue(UUID tenant,UUID sourceId,UUID actor) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        UUID id=tx.execute(status->{
            Source source=source(tenant,sourceId,true);
            if(!"APPROVED".equals(source.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approved tenant WMS key required");
            Long attempts=jdbc.queryForObject("""
                SELECT count(*) FROM finance_wms_key_challenge_v101
                WHERE tenant_id=? AND source_id=?
                  AND issued_at>now()-INTERVAL '24 hours'
                """,Long.class,tenant,sourceId);
            if(attempts!=null&&attempts>=5)throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,"At most five challenges per source each 24 hours");
            Integer recent=jdbc.queryForObject("""
                SELECT count(*) FROM finance_wms_key_challenge_v101
                WHERE tenant_id=? AND source_id=?
                  AND issued_at>now()-INTERVAL '1 minute'
                """,Integer.class,tenant,sourceId);
            if(recent!=null&&recent>0)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Challenge already issued in the last minute");
            byte[] nonce=new byte[32];random.nextBytes(nonce);
            String hex=HexFormat.of().formatHex(nonce);
            UUID challenge=UUID.randomUUID();
            // Bound to the source, warehouse, tenant, specific nonce and TTL.
            Instant expiry=Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String payload=canonical(tenant,sourceId,source.warehouse(),
                challenge,hex,expiry);
            jdbc.update("""
                INSERT INTO finance_wms_key_challenge_v101
                  (id,tenant_id,source_id,warehouse_id,nonce_hex,
                   canonical_sha256,issued_by,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,challenge,tenant,sourceId,source.warehouse(),hex,
                RefundRecoveryService.hash(payload),actor,OffsetDateTime.ofInstant(
                    expiry,java.time.ZoneOffset.UTC));
            return challenge;
        });
        return list(tenant,sourceId).stream()
            .filter(x->x.id().equals(id.toString())).findFirst().orElseThrow();
    }
    public Challenge verify(UUID tenant,UUID sourceId,UUID challengeId,UUID actor,
        ProofInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||input.signatureBase64()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Detached Ed25519 signature required");
        try {
            tx.executeWithoutResult(status->{
                Source source=source(tenant,sourceId,true);
                if(!"APPROVED".equals(source.state()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Signing source revoked or not yet approved");
                var rows=jdbc.query("""
                    SELECT id,source_id,warehouse_id,nonce_hex,canonical_sha256,
                       expires_at,issued_by
                    FROM finance_wms_key_challenge_v101
                    WHERE tenant_id=? AND source_id=? AND id=? AND warehouse_id=?
                    FOR UPDATE
                    """,(r,i)->new Row(r.getObject(1,UUID.class),
                        r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                        r.getString(4),r.getString(5),
                        r.getObject(6,OffsetDateTime.class).toInstant(),
                        r.getObject(7,UUID.class),null,null),
                    tenant,sourceId,challengeId,source.warehouse());
                if(rows.size()!=1)throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,"Challenge not in the requested source");
                Row row=rows.getFirst();
                RefundRecoveryService.requireIndependent(row.issuer(),actor);
                RefundRecoveryService.requireIndependent(source.registrar(),actor);
                if(!Instant.now().isBefore(row.expiry()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Key possession challenge expired");
                Integer existing=jdbc.queryForObject("""
                    SELECT count(*) FROM finance_wms_key_proof_v101
                    WHERE tenant_id=? AND challenge_id=?
                    """,Integer.class,tenant,challengeId);
                if(existing!=null&&existing>0)throw new ResponseStatusException(
                    HttpStatus.CONFLICT,"Challenge already used");
                String payload=canonical(tenant,sourceId,source.warehouse(),
                    challengeId,row.nonce(),row.expiry());
                if(!RefundRecoveryService.hash(payload).equals(row.digest())
                    ||!WmsSignedReturnService.verify(source.spki(),
                        input.signatureBase64(),payload))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Signature mismatch for exact one-time challenge");
                jdbc.update("""
                    INSERT INTO finance_wms_key_proof_v101
                     (id,tenant_id,challenge_id,source_id,warehouse_id,
                      signature_base64,canonical_sha256,verified_by)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,challengeId,sourceId,
                    source.warehouse(),input.signatureBase64(),row.digest(),actor);
            });
        } catch(DataIntegrityViolationException collision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate proof or independent actor constraint violated");
        }
        return list(tenant,sourceId).stream()
            .filter(x->x.id().equals(challengeId.toString())).findFirst().orElseThrow();
    }
}
