package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfflineIssuerCertificateTrustV209Test {
    private static final String LEAF_SHA =
        "c6408cdb6c9008edd6c245959ea00d9927b3b47f4f58506f5ab9d21a52dc01ae";
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-11-23T09:00:00Z"),ZoneOffset.UTC);
    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-"
            +String.format("%012x",n));
    }

    @TempDir Path dir;

    private String copy(String fixture,String to) throws Exception {
        var url=getClass().getResource("/v209/"+fixture);
        assertNotNull(url,"Synthetic V209 test fixture must exist");
        Path copied=dir.resolve(to);
        Files.copy(Path.of(url.toURI()),copied);
        return copied.toAbsolutePath().toString();
    }
    private OfflineIssuerCertificateTrustV209 configured(String root,
        String leaf,String crl,String operatorAllowed,String carrierAllowed,
        Clock clock) {
        return new OfflineIssuerCertificateTrustV209(
            true,root,leaf,crl,operatorAllowed,carrierAllowed,clock);
    }

    @Test void realOfflinePkixAndSignedFreshCrlMatchesPinnedEd25519()
        throws Exception {
        String root=copy("synthetic-root.pem","root.pem");
        String leaf=copy("synthetic-leaf.pem","issuer.pem");
        String crl=copy("synthetic-current.crl.pem","current.crl.pem");
        var svc=configured(root,leaf,crl,"issuer.operator.test","",FIXED_CLOCK);
        var result=svc.evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA);
        assertEquals("OFFLINE_PKIX_AND_CRL_MATCH_TENANT_KEY_EXTERNAL_IDENTITY_UNVERIFIED",
            result.state());
        assertTrue(result.chainValidForOperatorPinnedRoot());
        assertTrue(result.offlineRevocationCurrent());
        assertEquals(LEAF_SHA,result.leafPublicKeySha256());
        assertEquals(64,result.rootCertificateSha256().length());
        assertFalse(result.independentIssuerIdentityVerified(),
            "Even a complete self-signed synthetic PKIX root is not real external identity");
        assertFalse(svc.evaluate("CARRIER_HANDOFF","issuer.operator.test",LEAF_SHA)
            .independentIssuerIdentityVerified());
        assertEquals("CLAIMED_ISSUER_NOT_OPERATOR_ALLOWLISTED",
            svc.evaluate("CARRIER_HANDOFF","issuer.operator.test",LEAF_SHA).state());
        assertEquals("CLAIMED_ISSUER_NOT_OPERATOR_ALLOWLISTED",
            svc.evaluate("OPERATOR_REGISTRY","tenant-controlled.test",LEAF_SHA).state());
        assertEquals("TENANT_KEY_NOT_MATCHED_TO_OFFLINE_LEAF_CERT",
            svc.evaluate("OPERATOR_REGISTRY","issuer.operator.test",
                "f".repeat(64)).state());
    }

    @Test void revocationIsRecheckedFromDiskAndCannotBeCachedAsValid()
        throws Exception {
        String root=copy("synthetic-root.pem","root.pem");
        String leaf=copy("synthetic-leaf.pem","issuer.pem");
        String crl=copy("synthetic-current.crl.pem","live.crl.pem");
        var svc=configured(root,leaf,crl,"issuer.operator.test","",FIXED_CLOCK);
        assertTrue(svc.evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA)
            .offlineRevocationCurrent());
        // In-place file content replacement represents operator-published new CRL,
        // not a tenant action. The same running service must not cache old trust.
        Files.write(Path.of(crl),Files.readAllBytes(Path.of(
            getClass().getResource("/v209/synthetic-revoked.crl.pem").toURI())));
        var revoked=svc.evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA);
        assertEquals("CERTIFICATE_REVOKED_IN_OPERATOR_CRL",revoked.state());
        assertFalse(revoked.chainValidForOperatorPinnedRoot());
        assertFalse(revoked.offlineRevocationCurrent());
        assertFalse(revoked.independentIssuerIdentityVerified());
    }

    @Test void expiredMissingAndTamperedTrustMaterialFailClosed()
        throws Exception {
        String root=copy("synthetic-root.pem","root.pem");
        String leaf=copy("synthetic-leaf.pem","issuer.pem");
        String crl=copy("synthetic-current.crl.pem","current.crl.pem");
        var disabled=new OfflineIssuerCertificateTrustV209(
            false,root,leaf,crl,"issuer.operator.test","",FIXED_CLOCK);
        assertEquals("OPERATOR_OFFLINE_TRUST_DISABLED",
            disabled.evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA).state());
        var absent=configured(root,leaf,dir.resolve("missing.crl.pem").toString(),
            "issuer.operator.test","",FIXED_CLOCK);
        assertEquals("OFFLINE_CERTIFICATE_CHAIN_OR_TRUST_MATERIAL_NOT_VERIFIABLE",
            absent.evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA).state());

        // The trusted CRL has a bounded 7-day signing window. It expires
        // after Nov 29 2026 and cannot silently pass later re-evaluations.
        var stale=configured(root,leaf,crl,"issuer.operator.test","",
            Clock.fixed(Instant.parse("2026-12-10T00:00:00Z"),ZoneOffset.UTC));
        assertEquals("SIGNED_CURRENT_CRL_MISSING_OR_STALE",
            stale.evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA).state());
        Files.writeString(Path.of(crl),"not-a-signed-CRL");
        assertEquals("OFFLINE_CERTIFICATE_CHAIN_OR_TRUST_MATERIAL_NOT_VERIFIABLE",
            configured(root,leaf,crl,"issuer.operator.test","",FIXED_CLOCK)
                .evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA).state());
        Files.writeString(Path.of(root),"forged operator root");
        assertFalse(configured(root,leaf,crl,"issuer.operator.test","",FIXED_CLOCK)
            .evaluate("OPERATOR_REGISTRY","issuer.operator.test",LEAF_SHA)
            .chainValidForOperatorPinnedRoot());
    }

    @Test void noTenantProofCannotPassOperatorOfflineReadiness() {
        var jdbc=mock(JdbcTemplate.class);
        var v208=mock(CustodyIssuerAttestationServiceV208.class);
        var offline=mock(OfflineIssuerCertificateTrustV209.class);
        var service=new CustodyExternalTrustReadinessV209(jdbc,v208,offline);
        UUID tenant=id(1),mp=id(2),order=id(3);
        when(v208.preview(tenant,mp,order)).thenReturn(
            new CustodyIssuerAttestationServiceV208.Preview(
                order.toString(),mp.toString(),
                "TENANT_ENROLLED_CLAIMED_ISSUER_SIGNATURES_ONLY",
                1,2,List.of(),List.of(),false,false,false,false,null));
        var result=service.preview(tenant,mp,order);
        assertEquals(2,result.missingClaimedIssuerSignatures());
        assertEquals(0,result.offlineTrustHoldCount());
        assertTrue(result.files().isEmpty());
        assertFalse(result.independentlyVerifiedIssuerIdentity());
        assertFalse(result.amazonFulfillmentVerified());
        assertFalse(result.actualBatchCogsVerified());
        assertNull(result.realizedBatchCogs());
        assertTrue(result.blockers().contains(
            "INDEPENDENT_EXTERNAL_ISSUER_SOURCE_AND_LEGAL_IDENTITY_NOT_CERTIFIED"));
        verifyNoInteractions(offline);
    }

    @Test void evenCryptographicallyValidV208IsNotExternalAuthentication() {
        var proof=new CustodyIssuerAttestationServiceV208.SignedProof(
            id(9).toString(),id(4).toString(),id(6).toString(),
            id(7).toString(),"OPERATOR_REGISTRY","issuer.operator.test",
            "a".repeat(64),"2026-11-23T05:10:00Z",
            "SIGNATURE_VALID_FOR_TENANT_KEY_ONLY_EXTERNAL_UNVERIFIED",
            true,false,false,false,false,false,null);
        assertTrue(CustodyExternalTrustReadinessV209.currentV208(proof));
        var invalid=new CustodyIssuerAttestationServiceV208.SignedProof(
            proof.id(),proof.dossierId(),proof.originalFileId(),
            proof.issuerKeyId(),proof.documentKind(),proof.claimedIssuerRef(),
            proof.originalSha256(),proof.claimedIssuedAt(),
            "ISSUER_KEY_REVOKED_OR_UNREVIEWED",
            true,false,false,false,false,false,null);
        assertFalse(CustodyExternalTrustReadinessV209.currentV208(invalid));
    }

    @Test void ownerOnlyReadinessRouteNoStoreAndNoMutation() {
        var access=mock(TenantAccessService.class);
        var service=mock(CustodyExternalTrustReadinessV209.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var ctrl=new CustodyExternalTrustControllerV209(access,service);
        UUID tenant=id(1),mp=id(2),order=id(3);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        assertTrue(ctrl.preview(auth,request,mp,order).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).preview(tenant,mp,order);
    }
}
