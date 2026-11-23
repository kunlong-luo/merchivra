package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.purchasing.LotProofAvScanner;
import org.merchivra.erp.purchasing.LotProofBinaryService;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchCustodyOriginalV206Test {
    static UUID id(int x) {
        return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012x",x));
    }
    static byte[] samplePdf() {
        return "%PDF-1.7\nV206 SYNTHETIC PRIVATE OPERATOR\n".getBytes(StandardCharsets.US_ASCII);
    }
    @Test void reusesExactExistingMagicAndSha256Algorithms() {
        byte[] bytes=samplePdf();
        assertEquals("application/pdf",LotProofBinaryService.mediaType(bytes));
        assertEquals(64,LotProofBinaryService.digest(bytes).length());
        assertEquals("OPERATOR_REGISTRY",
            DispatchCustodyOriginalServiceV206.kind("OPERATOR_REGISTRY"));
        assertEquals("CARRIER_HANDOFF",
            DispatchCustodyOriginalServiceV206.kind("CARRIER_HANDOFF"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyOriginalServiceV206.kind("EXTERNAL_VERIFIED"))
                .getStatusCode());
        assertThrows(ResponseStatusException.class,
            ()->LotProofBinaryService.mediaType("not PDF".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ResponseStatusException.class,
            ()->LotProofBinaryService.mediaType(new byte[LotProofBinaryService.MAX_SIZE+1]));
    }
    @Test void readtimeOriginalConsistencyNeverPromotesExternalIdentity() {
        assertEquals("ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED",
            DispatchCustodyOriginalServiceV206.effectiveState(
                "ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED",
                "INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED"));
        for(String s:List.of("SIGNED_DISPATCH_STALE","EXTERNAL_REFERENCE_DIGEST_CONFLICT",
                "SIGNED_DISPATCH_RECOMPUTATION_FAILED_OR_STALE")) {
            assertEquals("DOSSIER_OR_SIGNED_SOURCE_STALE",
                DispatchCustodyOriginalServiceV206.effectiveState(
                    "ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED",s));
        }
        assertEquals("DOSSIER_OR_SIGNED_SOURCE_STALE",
            DispatchCustodyOriginalServiceV206.effectiveState(
                "ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED",null));
    }
    @Test void noDossierRemainsHardBlockedAndNoOriginalBytesExposed() {
        var jdbc=mock(JdbcTemplate.class);
        var custody=mock(DispatchCustodyDossierServiceV205.class);
        var scanner=mock(LotProofAvScanner.class);
        var service=new DispatchCustodyOriginalServiceV206(jdbc,custody,scanner);
        UUID tenant=id(1),mp=id(2),order=id(3);
        when(custody.preview(tenant,mp,order)).thenReturn(
            new DispatchCustodyDossierServiceV205.Preview(
                order.toString(),mp.toString(),
                "TENANT_DECLARED_EXTERNAL_CUSTODY_ONLY",0,0,List.of(),List.of(),
                false,false,false,false,null));
        var p=service.preview(tenant,mp,order);
        assertEquals(0,p.dossierCount());
        assertEquals(0,p.originalFilesMissing());
        assertTrue(p.dossiers().isEmpty());
        assertTrue(p.blockerCodes().contains(
            "EXTERNAL_ISSUER_NOT_AUTHENTICATED_BY_TRUSTED_CHANNEL"));
        assertTrue(p.blockerCodes().contains("AMAZON_FBA_FBM_FULFILLMENT_UNVERIFIED"));
        assertFalse(p.operatorIdentityVerified());
        assertFalse(p.platformFulfillmentVerified());
        assertFalse(p.actualBatchCogsVerified());
        assertNull(p.realizedBatchCogs());
        verifyNoInteractions(scanner);
    }
    @Test void scannerMustRunBeforeClaimLookupAndDatabaseWrite() {
        var jdbc=mock(JdbcTemplate.class);
        var custody=mock(DispatchCustodyDossierServiceV205.class);
        var scanner=mock(LotProofAvScanner.class);
        var service=new DispatchCustodyOriginalServiceV206(jdbc,custody,scanner);
        byte[] original=samplePdf();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "clamd not ready")).when(scanner).scan(original);
        var problem=assertThrows(ResponseStatusException.class,
            ()->service.upload(id(1),id(2),id(3),id(4),id(5),
                "OPERATOR_REGISTRY",original));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,problem.getStatusCode());
        verify(scanner).scan(original);
        verifyNoInteractions(jdbc,custody);
    }
    @Test void controllerScopesOwnerAndNeverOffersPrivateBinaryDownload() throws Exception {
        var access=mock(TenantAccessService.class);
        var service=mock(DispatchCustodyOriginalServiceV206.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=id(1),mp=id(2),order=id(3),dossier=id(4),actor=id(5);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var controller=new DispatchCustodyOriginalControllerV206(access,service);
        assertTrue(controller.preview(auth,request,mp,order)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,mp,order);
        byte[] binary=samplePdf();
        var file=new MockMultipartFile("file","operator.pdf","application/pdf",binary);
        var response=controller.upload(auth,request,mp,order,dossier,
            "OPERATOR_REGISTRY",file);
        assertEquals(HttpStatus.CREATED,response.getStatusCode());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).upload(tenant,mp,order,dossier,actor,"OPERATOR_REGISTRY",binary);
        assertThrows(ResponseStatusException.class,
            ()->controller.upload(auth,request,mp,order,dossier,"OPERATOR_REGISTRY",
                new MockMultipartFile("file","bad.txt","text/plain",new byte[0])));
    }
}
