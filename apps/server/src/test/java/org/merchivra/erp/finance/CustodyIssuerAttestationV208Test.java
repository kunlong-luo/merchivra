package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustodyIssuerAttestationV208Test {
    static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012x",n));
    }
    static String payload(String kind,String fileSha,UUID key,UUID file,UUID dossier) {
        return CustodyIssuerAttestationServiceV208.canonical(
            id(1),id(2),id(3),dossier,id(5),file,kind,fileSha,key,
            "OPERATOR-205","CLAIMED-ISSUER-205",
            OffsetDateTime.parse("2026-11-23T05:10:00Z"));
    }

    @Test void realEd25519ExactDocumentBindingAndDigestRecomputation() throws Exception {
        var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var second=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String spki=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        String otherSpki=Base64.getEncoder().encodeToString(second.getPublic().getEncoded());
        String original=payload("OPERATOR_REGISTRY","a".repeat(64),id(9),id(6),id(4));
        var signature=Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(original.getBytes(StandardCharsets.UTF_8));
        String detached=Base64.getEncoder().encodeToString(signature.sign());
        String hash=RefundRecoveryService.hash(original);
        assertTrue(CustodyIssuerAttestationServiceV208.cryptographicallyMatches(
            spki,detached,original,hash));
        assertFalse(CustodyIssuerAttestationServiceV208.cryptographicallyMatches(
            otherSpki,detached,original,hash));
        assertFalse(CustodyIssuerAttestationServiceV208.cryptographicallyMatches(
            spki,detached,original,"0".repeat(64)));
        for(var changed:List.of(
            payload("CARRIER_HANDOFF","a".repeat(64),id(9),id(6),id(4)),
            payload("OPERATOR_REGISTRY","b".repeat(64),id(9),id(6),id(4)),
            payload("OPERATOR_REGISTRY","a".repeat(64),id(10),id(6),id(4)),
            payload("OPERATOR_REGISTRY","a".repeat(64),id(9),id(7),id(4)),
            payload("OPERATOR_REGISTRY","a".repeat(64),id(9),id(6),id(99)))) {
            assertFalse(CustodyIssuerAttestationServiceV208.cryptographicallyMatches(
                spki,detached,changed,RefundRecoveryService.hash(changed)));
        }
        assertTrue(original.startsWith("MERCHIVRA_CUSTODY_CLAIMED_ISSUER_V1\n"));
        assertTrue(original.endsWith("2026-11-23T05:10:00Z\n"));
    }

    @Test void strictCanonicalIssuerReferencesCannotInjectNewlinesOrFalseVerification() {
        assertEquals("CLAIMED-ISSUER-205",
            CustodyIssuerAttestationServiceV208.issuerRef("CLAIMED-ISSUER-205"));
        for(String invalid:List.of("short","FAKE\nISSUER","SPACES IN CLAIM","A".repeat(161))) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->CustodyIssuerAttestationServiceV208.issuerRef(invalid))
                .getStatusCode());
        }
        assertThrows(ResponseStatusException.class,
            ()->payload("EXTERNAL_CERTIFIED","a".repeat(64),id(9),id(6),id(4)));
        assertThrows(ResponseStatusException.class,
            ()->payload("CARRIER_HANDOFF","not-a-hash",id(9),id(6),id(4)));
        assertThrows(ResponseStatusException.class,
            ()->CustodyIssuerAttestationServiceV208.issuedAt(
                OffsetDateTime.now().plusDays(1)));
        assertThrows(ResponseStatusException.class,
            ()->CustodyIssuerAttestationServiceV208.issuedAt(
                OffsetDateTime.now().minusYears(20)));
        assertNotNull(CustodyIssuerAttestationServiceV208.issuedAt(
            OffsetDateTime.now().minusDays(1)));
    }

    @Test void noIssuerSignaturesCannotClaimVerifiedExternalCarrierOrActualCogs() {
        var jdbc=mock(JdbcTemplate.class);
        var originals=mock(DispatchCustodyOriginalServiceV206.class);
        var svc=new CustodyIssuerAttestationServiceV208(jdbc,originals);
        UUID tenant=id(1),mp=id(2),order=id(3);
        when(originals.preview(tenant,mp,order)).thenReturn(
            new DispatchCustodyOriginalServiceV206.Preview(
                order.toString(),mp.toString(),
                "PRIVATE_ORIGINAL_CONTENT_MATCHES_TENANT_CLAIM_ONLY",
                1,0,List.of(),List.of(),false,false,false,null));
        var p=svc.preview(tenant,mp,order);
        assertEquals(2,p.missingIssuerSignatures());
        assertTrue(p.proofs().isEmpty());
        assertFalse(p.independentIssuerIdentityVerified());
        assertFalse(p.physicalHandoffVerified());
        assertFalse(p.amazonFulfillmentVerified());
        assertFalse(p.actualBatchCogsVerified());
        assertNull(p.realizedBatchCogs());
        assertTrue(p.blockers().contains(
            "INDEPENDENT_EXTERNAL_ISSUER_AND_CERTIFICATE_REVOCATION_SOURCE_MISSING"));
        verify(originals).preview(tenant,mp,order);
    }

    @Test void invalidPublicKeyRejectedBeforeAnyDatabaseWrites() {
        var jdbc=mock(JdbcTemplate.class);
        var svc=new CustodyIssuerAttestationServiceV208(
            jdbc,mock(DispatchCustodyOriginalServiceV206.class));
        var invalid=new CustodyIssuerAttestationServiceV208.RegisterIssuer(
            "OPERATOR_REGISTRY","CLAIMED-ISSUER-205","invalid-key");
        var status=assertThrows(ResponseStatusException.class,
            ()->svc.register(id(1),id(2),invalid));
        assertEquals(HttpStatus.BAD_REQUEST,status.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void ownerScopedRoutesNoStoreAndNoPrivateOriginalDownload() {
        var access=mock(TenantAccessService.class);
        var service=mock(CustodyIssuerAttestationServiceV208.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var controller=new CustodyIssuerAttestationControllerV208(access,service);
        UUID tenant=id(1),mp=id(2),order=id(3),dossier=id(4),issuer=id(9),actor=id(8);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        assertTrue(controller.keys(auth,request).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).issuers(tenant);
        var registration=new CustodyIssuerAttestationServiceV208.RegisterIssuer(
            "CARRIER_HANDOFF","CLAIMED-CARRIER-205","synthetic-only");
        assertTrue(controller.register(auth,request,registration).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).register(tenant,actor,registration);
        var decision=new CustodyIssuerAttestationServiceV208.Decision(
            "Internal review of tenant enrolled issuer claim");
        assertTrue(controller.review(auth,request,issuer,decision).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).reviewIssuer(tenant,issuer,actor,decision);
        assertTrue(controller.revoke(auth,request,issuer,decision).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).revokeIssuer(tenant,issuer,actor,decision);
        assertTrue(controller.preview(auth,request,mp,order).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).preview(tenant,mp,order);
        var signed=new CustodyIssuerAttestationServiceV208.SignedInput(issuer,
            OffsetDateTime.parse("2026-11-23T05:10:00Z"),"synthetic-signature");
        assertTrue(controller.signed(auth,request,mp,order,dossier,
            "CARRIER_HANDOFF",signed).getHeaders().getCacheControl().contains("no-store"));
        verify(service).submit(tenant,mp,order,dossier,"CARRIER_HANDOFF",actor,signed);
    }
}
