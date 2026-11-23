package org.merchivra.erp.finance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
 * V208 cryptographic proof of possession of a TENANT-ENROLLED claimed issuer
 * Ed25519 key, never independently authenticated external legal issuer identity.
 *
 * All claimed signatures are bound to V205 dispatch and V206 original content,
 * and recomputed for every GET. Source revocation invalidates older claims.
 * No external network access, file disclosure, inventory or COGS posting.
 */
@Service
public class CustodyIssuerAttestationServiceV208 {
    public record RegisterIssuer(String documentKind,String claimedIssuerRef,
        String publicKeySpkiBase64) {}
    public record Decision(String reason) {}
    public record SignedInput(UUID issuerKeyId,OffsetDateTime claimedIssuedAt,
        String signatureBase64) {}
    public record Issuer(String id,String documentKind,String claimedIssuerRef,
        String publicKeySha256,String state,String registeredBy,String reviewedBy,
        boolean independentIssuerIdentityVerified,boolean externalTrustAnchorVerified) {}
    public record SignedProof(String id,String dossierId,String originalFileId,
        String issuerKeyId,String documentKind,String claimedIssuerRef,
        String originalSha256,String claimedIssuedAt,String evidenceState,
        boolean signatureValidForTenantKey,
        boolean independentIssuerIdentityVerified,boolean externalTrustAnchorVerified,
        boolean physicalHandoffVerified,boolean amazonFulfillmentVerified,
        boolean actualBatchCogsVerified,String realizedBatchCogs) {}
    public record Preview(String orderId,String marketplaceId,String scope,
        int dossierCount,int missingIssuerSignatures,List<SignedProof> proofs,
        List<String> blockers,boolean independentIssuerIdentityVerified,
        boolean physicalHandoffVerified,boolean amazonFulfillmentVerified,
        boolean actualBatchCogsVerified,String realizedBatchCogs) {}

    private record Key(UUID id,String kind,String issuerRef,String publicKey,
        String fingerprint,String state,UUID registeredBy,UUID reviewedBy) {}
    private record Identity(UUID dossier,UUID mp,UUID order,UUID dispatch,
        UUID file,String kind,String originalSha,String documentRef,
        String originalState,UUID uploader,UUID submitter) {}
    private record Attestation(UUID id,UUID dossier,UUID file,UUID key,
        String kind,UUID mp,UUID order,UUID dispatch,String documentRef,
        String originalSha,String issuerRef,OffsetDateTime issuedAt,
        String signature,String digest,String spki,String dbState) {}

    private static final String SELECT_KEYS = """
        SELECT id,document_kind,claimed_issuer_ref,ed25519_spki_base64,
          key_sha256,state,registered_by,reviewed_by
        FROM finance_custody_issuer_key_v111
        WHERE tenant_id=?
        """;
    private static final String SELECT_PROOFS = """
        SELECT id,dossier_id,original_file_id,issuer_key_id,document_kind,
          snapshot_marketplace_id,snapshot_sales_order_id,
          snapshot_signed_dispatch_id,snapshot_document_ref,
          snapshot_file_sha256,snapshot_issuer_ref,claimed_issued_at,
          signature_base64,canonical_sha256,ed25519_spki_base64,evidence_state
        FROM finance_custody_issuer_signature_integrity_v111
        WHERE tenant_id=? AND current_marketplace_id=? AND current_sales_order_id=?
        ORDER BY id LIMIT 1001
        """;
    private final JdbcTemplate jdbc;
    private final DispatchCustodyOriginalServiceV206 originals;

    public CustodyIssuerAttestationServiceV208(JdbcTemplate jdbc,
        DispatchCustodyOriginalServiceV206 originals) {
        this.jdbc=jdbc;this.originals=originals;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }
    static String kind(String input) {
        return DispatchCustodyOriginalServiceV206.kind(input);
    }
    static String issuerRef(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw bad("A claimed issuer reference of 8..160 safe characters is required");
        return value;
    }
    static String canonical(UUID tenant,UUID mp,UUID order,UUID dossier,
        UUID dispatch,UUID file,String documentKind,String sha,UUID keyId,
        String reference,String claimedIssuerRef,OffsetDateTime issuedAt) {
        if(tenant==null||mp==null||order==null||dossier==null||dispatch==null
            ||file==null||keyId==null||issuedAt==null
            ||sha==null||!sha.matches("[0-9a-f]{64}"))
            throw bad("Complete and canonical original-file identity required");
        kind(documentKind);
        issuerRef(reference);
        issuerRef(claimedIssuerRef);
        return "MERCHIVRA_CUSTODY_CLAIMED_ISSUER_V1\n"
            +tenant+"\n"+mp+"\n"+order+"\n"+dossier+"\n"
            +dispatch+"\n"+file+"\n"+documentKind+"\n"+sha+"\n"
            +keyId+"\n"+reference+"\n"+claimedIssuerRef+"\n"
            +issuedAt.toInstant()+"\n";
    }
    static Instant issuedAt(OffsetDateTime value) {
        Instant now=Instant.now();
        if(value==null||value.toInstant().isBefore(now.minus(3650,ChronoUnit.DAYS))
            ||value.toInstant().isAfter(now.plus(10,ChronoUnit.MINUTES)))
            throw bad("Issuer claimed signing timestamp outside 10-year/10-minute bound");
        return value.toInstant();
    }
    static boolean cryptographicallyMatches(String publicKey,String signature,
        String canonical,String capturedSha256) {
        try {
            return Objects.equals(RefundRecoveryService.hash(canonical),capturedSha256)
                && WmsSignedReturnService.verify(publicKey,signature,canonical);
        } catch(RuntimeException invalid) {
            return false;
        }
    }

    private static String verifiedState(Attestation a,
        String baselineOriginalState,UUID tenant) {
        if(!"TENANT_KEY_SOURCE_CURRENT_CRYPTO_RECOMPUTE_REQUIRED".equals(a.dbState())
            ||!"ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED".equals(baselineOriginalState))
            return "ISSUER_SOURCE_OR_ORIGINAL_STALE";
        try {
            String payload=canonical(tenant,a.mp(),a.order(),a.dossier(),
                a.dispatch(),a.file(),a.kind(),a.originalSha(),a.key(),
                a.documentRef(),a.issuerRef(),a.issuedAt());
            if(!cryptographicallyMatches(a.spki(),a.signature(),payload,a.digest()))
                return "ISSUER_SIGNATURE_RECOMPUTATION_FAILED";
        } catch(RuntimeException invalid) {
            return "ISSUER_SIGNATURE_RECOMPUTATION_FAILED";
        }
        return "SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED";
    }

    private static Key mapKey(java.sql.ResultSet r,int i)
        throws java.sql.SQLException {
        return new Key(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
            r.getString(4),r.getString(5),r.getString(6),
            r.getObject(7,UUID.class),r.getObject(8,UUID.class));
    }
    private Key source(UUID tenant,UUID sourceId,boolean lock) {
        if(sourceId==null)throw bad("Claimed issuer key UUID required");
        String sql=SELECT_KEYS+" AND id=?"+(lock?" FOR UPDATE":"");
        var result=jdbc.query(sql,CustodyIssuerAttestationServiceV208::mapKey,tenant,sourceId);
        return result.stream().findFirst().orElseThrow(
            ()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Tenant-scoped claimed issuer key not found"));
    }
    private Issuer publicIssuer(Key k) {
        return new Issuer(k.id().toString(),k.kind(),k.issuerRef(),
            k.fingerprint(),k.state(),k.registeredBy().toString(),
            k.reviewedBy()==null?null:k.reviewedBy().toString(),false,false);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Issuer> issuers(UUID tenant) {
        var keys=jdbc.query(SELECT_KEYS+" ORDER BY id LIMIT 1001",
            CustodyIssuerAttestationServiceV208::mapKey,tenant);
        if(keys.size()>1000)throw conflict("Claimed issuer key listing requires pagination");
        return keys.stream().map(this::publicIssuer).toList();
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Issuer register(UUID tenant,UUID actor,RegisterIssuer input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null)throw bad("Public claimed issuer key required");
        String k=kind(input.documentKind());
        String ref=issuerRef(input.claimedIssuerRef());
        byte[] der=WmsSignedReturnService.decoded(input.publicKeySpkiBase64(),128);
        WmsSignedReturnService.publicKey(input.publicKeySpkiBase64());
        if(!Base64.getEncoder().encodeToString(der).equals(input.publicKeySpkiBase64()))
            throw bad("Canonical base64 DER Ed25519 public key required");
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_custody_issuer_key_v111
                (id,tenant_id,document_kind,claimed_issuer_ref,
                 ed25519_spki_base64,registered_by)
                VALUES (?,?,?,?,?,?)
                """,id,tenant,k,ref,input.publicKeySpkiBase64(),actor);
        } catch(DataIntegrityViolationException e) {
            throw conflict("Claimed issuer key already enrolled or tenant mismatch");
        }
        return publicIssuer(source(tenant,id,false));
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Issuer reviewIssuer(UUID tenant,UUID issuerId,UUID actor,Decision decision) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=DispatchCustodyOriginalGovernanceServiceV207.reason(
            decision==null?null:decision.reason());
        Key k=source(tenant,issuerId,true);
        RefundRecoveryService.requireIndependent(k.registeredBy(),actor);
        if(!"REGISTERED_UNVERIFIED".equals(k.state()))
            throw conflict("Claimed issuer key already reviewed or revoked");
        try {
            jdbc.update("""
                UPDATE finance_custody_issuer_key_v111
                SET state='REVIEWED_UNVERIFIED',reviewed_by=?,reviewed_at=now(),
                    review_reason=?
                WHERE tenant_id=? AND id=? AND state='REGISTERED_UNVERIFIED'
                """,actor,note,tenant,issuerId);
        } catch(DataIntegrityViolationException e) {
            throw conflict("Reviewer independence or registered key conflict");
        }
        return publicIssuer(source(tenant,issuerId,false));
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Issuer revokeIssuer(UUID tenant,UUID issuerId,UUID actor,Decision decision) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String note=DispatchCustodyOriginalGovernanceServiceV207.reason(
            decision==null?null:decision.reason());
        Key k=source(tenant,issuerId,true);
        if("REVOKED".equals(k.state()))throw conflict("Issuer key already revoked");
        try {
            jdbc.update("""
                UPDATE finance_custody_issuer_key_v111
                SET state='REVOKED',revoked_by=?,revoked_at=now(),revoke_reason=?
                WHERE tenant_id=? AND id=? AND state<>'REVOKED'
                """,actor,note,tenant,issuerId);
        } catch(DataIntegrityViolationException e) {
            throw conflict("Issuer key revocation failed");
        }
        return publicIssuer(source(tenant,issuerId,false));
    }

    private Identity identity(UUID tenant,UUID mp,UUID order,UUID dossier,String k) {
        var matches=jdbc.query("""
            SELECT d.id,d.marketplace_id,d.sales_order_id,d.signed_dispatch_id,
              o.id,o.document_kind,o.sha256,
              CASE WHEN o.document_kind='OPERATOR_REGISTRY'
                THEN d.operator_registry_ref ELSE d.carrier_handoff_ref END,
              v.original_state,o.uploaded_by,d.submitted_by
            FROM finance_dispatch_custody_dossier_v108 d
            JOIN finance_dispatch_custody_original_v109 o
              ON o.tenant_id=d.tenant_id AND o.dossier_id=d.id
            JOIN finance_dispatch_custody_original_integrity_v109 v
              ON v.tenant_id=d.tenant_id AND v.dossier_id=d.id
            WHERE d.tenant_id=? AND d.marketplace_id=? AND d.sales_order_id=?
              AND d.id=? AND o.document_kind=?
            FOR UPDATE OF o
            """,(r,i)->new Identity(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getObject(4,UUID.class),r.getObject(5,UUID.class),
                r.getString(6),r.getString(7),r.getString(8),r.getString(9),
                r.getObject(10,UUID.class),r.getObject(11,UUID.class)),
            tenant,mp,order,dossier,k);
        return matches.stream().findFirst().orElseThrow(
            ()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Current tenant/order custody original not found"));
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public SignedProof submit(UUID tenant,UUID mp,UUID order,UUID dossier,
        String documentKind,UUID actor,SignedInput input) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(input==null||input.issuerKeyId()==null||input.signatureBase64()==null)
            throw bad("Exact issuer key and Ed25519 detached signature required");
        String k=kind(documentKind);
        issuedAt(input.claimedIssuedAt());
        var baseline=originals.preview(tenant,mp,order);
        boolean current=baseline.dossiers().stream().anyMatch(
            x->x.dossierId().equals(dossier.toString())
              &&"ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED".equals(x.originalState()));
        if(!current)throw conflict("V206 original bytes or V204/V205 evidence stale");
        Key key=source(tenant,input.issuerKeyId(),true);
        if(!k.equals(key.kind())||!"REVIEWED_UNVERIFIED".equals(key.state()))
            throw conflict("Issuer kind not matching independently reviewed tenant key");
        RefundRecoveryService.requireIndependent(key.registeredBy(),actor);
        Identity linked=identity(tenant,mp,order,dossier,k);
        if(!"ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED".equals(linked.originalState()))
            throw conflict("Private original SHA or upstream dispatch identity invalid");
        RefundRecoveryService.requireIndependent(linked.uploader(),actor);
        RefundRecoveryService.requireIndependent(linked.submitter(),actor);
        String payload=canonical(tenant,mp,order,dossier,linked.dispatch(),
            linked.file(),k,linked.originalSha(),key.id(),linked.documentRef(),
            key.issuerRef(),input.claimedIssuedAt());
        if(!WmsSignedReturnService.verify(key.publicKey(),input.signatureBase64(),payload))
            throw conflict("Ed25519 signed issuer document payload is not exact");
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_custody_issuer_signature_v111
                  (id,tenant_id,dossier_id,original_file_id,issuer_key_id,
                   document_kind,claimed_issued_at,signature_base64,
                   canonical_sha256,imported_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,dossier,linked.file(),key.id(),k,
                input.claimedIssuedAt(),input.signatureBase64(),
                RefundRecoveryService.hash(payload),actor);
        } catch(DataIntegrityViolationException e) {
            throw conflict("Duplicate, stale or tenant-unsafe claimed issuer signature");
        }
        return preview(tenant,mp,order).proofs().stream()
            .filter(x->x.id().equals(id.toString())).findFirst().orElseThrow();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID mp,UUID order) {
        var base=originals.preview(tenant,mp,order);
        Map<String,String> originalStates=new HashMap<>();
        for(var d:base.dossiers()) {
            if(originalStates.putIfAbsent(d.dossierId(),d.originalState())!=null)
                throw conflict("Duplicate original dossier binding");
        }
        var evidence=jdbc.query(SELECT_PROOFS,(r,i)->new Attestation(
            r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getObject(4,UUID.class),
            r.getString(5),r.getObject(6,UUID.class),
            r.getObject(7,UUID.class),r.getObject(8,UUID.class),
            r.getString(9),r.getString(10),r.getString(11),
            r.getObject(12,OffsetDateTime.class),r.getString(13),
            r.getString(14),r.getString(15),r.getString(16)),
            tenant,mp,order);
        if(evidence.size()>1000)throw conflict("Issuer signature proof listing not bounded");
        var result=new ArrayList<SignedProof>();
        var blockers=new ArrayList<String>();
        blockers.add("CLAIMED_ISSUER_KEY_TENANT_ENROLLED_NOT_EXTERNAL_CERTIFICATE_AUTHENTICATED");
        blockers.add("INDEPENDENT_EXTERNAL_ISSUER_AND_CERTIFICATE_REVOCATION_SOURCE_MISSING");
        blockers.add("AMAZON_FBA_FBM_FULFILLMENT_UNVERIFIED");
        blockers.add("G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED");
        boolean hasInvalid=false;
        for(var r:evidence) {
            String state=verifiedState(r,originalStates.get(r.dossier().toString()),tenant);
            if(!"SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED".equals(state))
                hasInvalid=true;
            result.add(new SignedProof(r.id().toString(),r.dossier().toString(),
                r.file().toString(),r.key().toString(),r.kind(),r.issuerRef(),
                r.originalSha(),r.issuedAt().toInstant().toString(),state,
                "SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED".equals(state),
                false,false,false,false,false,null));
        }
        int missing=Math.max(0,base.dossierCount()*2-evidence.size());
        if(missing>0)blockers.add("OPERATOR_OR_CARRIER_ISSUER_SIGNATURE_MISSING");
        if(hasInvalid)blockers.add("ISSUER_KEY_REVOKED_OR_SIGNATURE_RECOMPUTATION_FAILED");
        if(!base.blockerCodes().isEmpty()
            &&base.blockerCodes().contains("ORIGINAL_BYTES_OR_UPSTREAM_EVIDENCE_NOT_READY"))
            blockers.add("UPSTREAM_ORIGINAL_EVIDENCE_NOT_CURRENT");
        return new Preview(order.toString(),mp.toString(),
            "TENANT_ENROLLED_CLAIMED_ISSUER_SIGNATURES_ONLY",
            base.dossierCount(),missing,List.copyOf(result),List.copyOf(blockers),
            false,false,false,false,null);
    }
}
