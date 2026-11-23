package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
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

class DispatchCustodyDossierV205Test {
    static UUID id(int x) {
        return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012x",x));
    }
    static SaleLotSignedDispatchServiceV204.SignedObservation signed(String state,
        boolean signatureValid) {
        return new SaleLotSignedDispatchServiceV204.SignedObservation(
            id(10).toString(),id(11).toString(),id(12).toString(),
            "DISPATCH-205",3,"2026-11-23T05:10:00Z",state,signatureValid,
            false,false,false,false,null);
    }

    @Test void fieldsRejectDelimiterInjectionFakeDigestAndFakeReviewPromotion() {
        assertEquals("OPERATOR-REG-205",
            DispatchCustodyDossierServiceV205.reference("OPERATOR-REG-205"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyDossierServiceV205.reference(
                "OPERATOR-REG-205\nFORGED")).getStatusCode());
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyDossierServiceV205.reference("ABC"));
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyDossierServiceV205.declaredDigest("not-a-sha256"));
        assertEquals("a".repeat(64),
            DispatchCustodyDossierServiceV205.declaredDigest("a".repeat(64)));
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyDossierServiceV205.decision("OPERATOR_VERIFIED"));
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyDossierServiceV205.decision("ACTUAL_COGS_APPROVED"));
        assertEquals("DOCS_CONSISTENT_UNVERIFIED",
            DispatchCustodyDossierServiceV205.decision("DOCS_CONSISTENT_UNVERIFIED"));
    }

    @Test void signedEvidenceInvalidationAlwaysOverridesPositiveInternalReview() {
        assertEquals("INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED",
            DispatchCustodyDossierServiceV205.effectiveState(
                "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED",
                signed("TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED",true)));
        assertEquals("SIGNED_DISPATCH_RECOMPUTATION_FAILED_OR_STALE",
            DispatchCustodyDossierServiceV205.effectiveState(
                "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED",
                signed("SIGNING_SOURCE_REVOKED",true)));
        assertEquals("SIGNED_DISPATCH_RECOMPUTATION_FAILED_OR_STALE",
            DispatchCustodyDossierServiceV205.effectiveState(
                "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED",
                signed("TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED",false)));
        assertEquals("SIGNED_DISPATCH_RECOMPUTATION_FAILED_OR_STALE",
            DispatchCustodyDossierServiceV205.effectiveState(
                "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED",null));
    }

    @Test void noDossierMustBlockRealOperatorPhysicalAndFinancialVerification() {
        var jdbc=mock(JdbcTemplate.class);
        var signed=mock(SaleLotSignedDispatchServiceV204.class);
        var svc=new DispatchCustodyDossierServiceV205(jdbc,signed);
        UUID tenant=id(1),mp=id(2),order=id(3);
        when(signed.preview(tenant,mp,order)).thenReturn(
            new SaleLotSignedDispatchServiceV204.Preview(
                order.toString(),mp.toString(),"SIGNED_LOCAL_DISPATCH_UNVERIFIED_EXTERNAL",
                1,0,List.of(signed("TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED",true)),
                List.of("AMAZON_FBA_FBM_FULFILLMENT_EVIDENCE_UNAVAILABLE"),
                false,false,null));
        var preview=svc.preview(tenant,mp,order);
        assertEquals(1,preview.signedDispatchCount());
        assertEquals(1,preview.missingDossierCount());
        assertTrue(preview.dossiers().isEmpty());
        assertTrue(preview.blockerCodes().contains(
            "EXTERNAL_OPERATOR_REGISTRY_SOURCE_NOT_INDEPENDENTLY_AUTHENTICATED"));
        assertTrue(preview.blockerCodes().contains(
            "CARRIER_HANDOFF_OR_PHYSICAL_CUSTODY_NOT_INDEPENDENTLY_VERIFIED"));
        assertFalse(preview.operatorIdentityVerified());
        assertFalse(preview.physicalHandoffVerified());
        assertFalse(preview.platformFulfillmentVerified());
        assertFalse(preview.actualBatchCogsVerified());
        assertNull(preview.realizedBatchCogs());
    }

    @Test void sameClaimedDocumentCannotBeBothRegistryAndCarrierProof() {
        var jdbc=mock(JdbcTemplate.class);
        var signed=mock(SaleLotSignedDispatchServiceV204.class);
        var svc=new DispatchCustodyDossierServiceV205(jdbc,signed);
        var input=new DispatchCustodyDossierServiceV205.Submit(
            "OPERATOR-REG-205","a".repeat(64),"CARRIER-REF-205","a".repeat(64),
            OffsetDateTime.now());
        var error=assertThrows(ResponseStatusException.class,
            ()->svc.submit(id(1),id(2),id(3),id(4),id(5),input));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        verifyNoInteractions(signed);
    }

    @Test void controllerKeepsTenantOwnerScopeAndNoStore() {
        var access=mock(TenantAccessService.class);
        var svc=mock(DispatchCustodyDossierServiceV205.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=id(1),mp=id(2),order=id(3),dispatch=id(4),dossier=id(5),actor=id(6);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var controller=new DispatchCustodyDossierControllerV205(access,svc);
        assertTrue(controller.preview(auth,request,mp,order).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,mp,order);
        var input=new DispatchCustodyDossierServiceV205.Submit(
            "OPERATOR-REG-205","a".repeat(64),"CARRIER-REF-205","b".repeat(64),
            OffsetDateTime.parse("2026-11-23T05:10:00Z"));
        assertTrue(controller.submit(auth,request,mp,order,dispatch,input)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(svc).submit(tenant,mp,order,dispatch,actor,input);
        var review=new DispatchCustodyDossierServiceV205.ReviewInput(
            "NEEDS_EVIDENCE","Missing source registry authentication");
        assertTrue(controller.review(auth,request,mp,order,dossier,review)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(svc).review(tenant,mp,order,dossier,actor,review);
    }
}
