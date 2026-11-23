package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V209: read-only comparison of the already independently recalculated V208
 * Ed25519 proof with DEPLOYMENT-operator-provisioned offline PKIX/CRL material.
 * These runtime files are not tenant-editable and never enter the database.
 *
 * Even a complete PKIX chain, current signed CRL and tenant-key Ed25519 proof
 * cannot independently establish the real legal issuer without source/anchor
 * provenance checks. No B2, Amazon fulfillment, inventory or actual COGS.
 */
@Service
public class CustodyExternalTrustReadinessV209 {
    public record FileTrust(String dossierId,String signedProofId,
        String documentKind,String claimedIssuerRef,String originalSha256,
        String v208EvidenceState,String operatorOfflineTrustState,
        boolean v208SignatureValidForTenantKey,
        boolean pinnedCertificateChainValid,
        boolean signedOfflineRevocationCurrent,
        boolean independentlyVerifiedIssuerIdentity,
        boolean physicalHandoffVerified,boolean amazonFulfillmentVerified,
        boolean actualBatchCogsVerified,String realizedBatchCogs) {}

    public record Preview(String orderId,String marketplaceId,String scope,
        int dossierCount,int missingClaimedIssuerSignatures,
        int offlineTrustHoldCount,List<FileTrust> files,
        List<String> blockers,boolean independentlyVerifiedIssuerIdentity,
        boolean physicalHandoffVerified,boolean amazonFulfillmentVerified,
        boolean actualBatchCogsVerified,String realizedBatchCogs) {}

    private final JdbcTemplate jdbc;
    private final CustodyIssuerAttestationServiceV208 issuer;
    private final OfflineIssuerCertificateTrustV209 offline;

    public CustodyExternalTrustReadinessV209(JdbcTemplate jdbc,
        CustodyIssuerAttestationServiceV208 issuer,
        OfflineIssuerCertificateTrustV209 offline) {
        this.jdbc=jdbc;this.issuer=issuer;this.offline=offline;
    }

    static boolean currentV208(CustodyIssuerAttestationServiceV208.SignedProof proof) {
        return proof.signatureValidForTenantKey()
            && "SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED"
               .equals(proof.evidenceState());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID marketplace,UUID order) {
        var baseline=issuer.preview(tenant,marketplace,order);
        var keys=jdbc.query("""
            SELECT id,key_sha256 FROM finance_custody_issuer_key_v111
            WHERE tenant_id=? ORDER BY id LIMIT 1001
            """,(rs,i)->Map.entry(rs.getObject(1,UUID.class),rs.getString(2)),tenant);
        if(keys.size()>1000)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Unbounded claimed-issuer key registry");
        Map<UUID,String> fingerprints=new HashMap<>();
        for(var key:keys)
            if(fingerprints.putIfAbsent(key.getKey(),key.getValue())!=null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate tenant issuer key identity");

        var files=new ArrayList<FileTrust>();
        int holds=0;
        for(var p:baseline.proofs()) {
            boolean valid=currentV208(p);
            var result=valid
                ?offline.evaluate(p.documentKind(),p.claimedIssuerRef(),
                    fingerprints.get(UUID.fromString(p.issuerKeyId())))
                :new OfflineIssuerCertificateTrustV209.Verdict(
                    "V208_SIGNATURE_OR_UPSTREAM_EVIDENCE_INVALID",
                    false,false,null,null,null,false);
            if(!result.chainValidForOperatorPinnedRoot()
                ||!result.offlineRevocationCurrent() ||!valid)holds++;
            files.add(new FileTrust(p.dossierId(),p.id(),p.documentKind(),
                p.claimedIssuerRef(),p.originalSha256(),p.evidenceState(),
                result.state(),valid,
                result.chainValidForOperatorPinnedRoot(),
                result.offlineRevocationCurrent(),
                false,false,false,false,null));
        }
        var blockers=new ArrayList<String>();
        blockers.add("OPERATOR_PINNED_PKIX_IS_NOT_PROOF_OF_EXTERNAL_ISSUER_AUTHORITY");
        blockers.add("INDEPENDENT_EXTERNAL_ISSUER_SOURCE_AND_LEGAL_IDENTITY_NOT_CERTIFIED");
        blockers.add("PHYSICAL_CARRIER_HANDOFF_NOT_INDEPENDENTLY_CONFIRMED");
        blockers.add("AMAZON_FBA_FBM_FULFILLMENT_UNVERIFIED");
        blockers.add("G1_ACTUAL_BATCH_COGS_AND_REALIZED_NET_PROFIT_NOT_AUTHORIZED");
        if(holds>0)blockers.add("OFFLINE_CERTIFICATE_CHAIN_OR_CRL_HOLD");
        if(baseline.missingIssuerSignatures()>0)
            blockers.add("OPERATOR_OR_CARRIER_CLAIMED_SIGNATURE_MISSING");
        return new Preview(order.toString(),marketplace.toString(),
            "OPERATOR_OFFLINE_PKIX_CRL_TECHNICAL_READINESS_NOT_REAL_IDENTITY",
            baseline.dossierCount(),baseline.missingIssuerSignatures(),holds,
            List.copyOf(files),List.copyOf(blockers),false,false,false,false,null);
    }
}
