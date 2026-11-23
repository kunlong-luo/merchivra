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

class SaleLotSignedDispatchV204Test {
    private static UUID id(int x) {
        return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012x",x));
    }
    private static String canonical(int quantity,UUID claim,UUID source,String ref) {
        return SaleLotSignedDispatchServiceV204.canonical(
            id(1),id(2),id(3),claim,id(5),id(6),id(7),id(8),source,
            ref,"AMZITEM-10","a".repeat(64),quantity,
            OffsetDateTime.parse("2026-11-23T05:10:00Z"));
    }
    @Test void realEd25519OnlyBindsExactLocalClaimAndNoExternalIdentity() throws Exception {
        var kp=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var verifierKey=Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String payload=canonical(2,id(4),id(9),"DISPATCH-1234");
        var signature=Signature.getInstance("Ed25519");
        signature.initSign(kp.getPrivate());
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        String detached=Base64.getEncoder().encodeToString(signature.sign());
        assertTrue(WmsSignedReturnService.verify(verifierKey,detached,payload));
        assertFalse(WmsSignedReturnService.verify(verifierKey,detached,
            canonical(3,id(4),id(9),"DISPATCH-1234")));
        assertFalse(WmsSignedReturnService.verify(verifierKey,detached,
            canonical(2,id(99),id(9),"DISPATCH-1234")));
        assertFalse(WmsSignedReturnService.verify(verifierKey,detached,
            canonical(2,id(4),id(10),"DISPATCH-1234")));
        assertFalse(WmsSignedReturnService.verify(verifierKey,detached,
            canonical(2,id(4),id(9),"DISPATCH-5678")));
        assertEquals(64,java.util.HexFormat.of().parseHex(
            RefundRecoveryService.hash(payload)).length*2);
    }
    @Test void canonicalPayloadRejectsNewlineAmbiguityAndUnboundedQuantity() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->canonical(2,id(4),id(9),"DISPATCH-1234\nFORGED")).getStatusCode());
        assertThrows(ResponseStatusException.class,()->canonical(0,id(4),id(9),
            "DISPATCH-1234"));
        assertThrows(ResponseStatusException.class,()->canonical(1000001,id(4),id(9),
            "DISPATCH-1234"));
    }
    @Test void noWmsProofNeverMeansActualProfitOrCogs() {
        var jdbc=mock(JdbcTemplate.class);
        var local=mock(OrderLotAssignmentIntegrityServiceV203.class);
        var svc=new SaleLotSignedDispatchServiceV204(jdbc,local);
        UUID tenant=id(1),mp=id(2),order=id(3);
        when(local.preview(tenant,mp,order)).thenReturn(
            new OrderLotAssignmentIntegrityServiceV203.Preview(
                order.toString(),mp.toString(),"COGS_READY_PREVIEW",
                "V1","LOCAL_OUTBOUND_UNVERIFIED_EXTERNAL",List.of(),List.of(),
                false,false,null));
        var p=svc.preview(tenant,mp,order);
        assertEquals(0,p.localClaimCount());
        assertTrue(p.evidence().isEmpty());
        assertFalse(p.platformFulfillmentVerified());
        assertFalse(p.actualBatchCogsVerified());
        assertNull(p.realizedBatchCogs());
        assertTrue(p.blockerCodes().contains(
            "AMAZON_FBA_FBM_FULFILLMENT_EVIDENCE_UNAVAILABLE"));
        assertTrue(p.blockerCodes().contains(
            "TENANT_ENROLLED_WMS_KEY_NOT_EXTERNAL_OPERATOR_ATTESTATION"));
    }
    @Test void sourceAndClaimAreAlwaysTenantAndOwnerScoped() {
        var access=mock(TenantAccessService.class);
        var svc=mock(SaleLotSignedDispatchServiceV204.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=id(1),mp=id(2),order=id(3),claim=id(4),user=id(5);
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(user);
        var ctl=new SaleLotSignedDispatchControllerV204(access,svc);
        assertTrue(ctl.preview(auth,req,mp,order)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,mp,order);
        var input=new SaleLotSignedDispatchServiceV204.SignedInput(id(8),2,
            OffsetDateTime.parse("2026-11-23T05:10:00Z"),"base64-signature");
        assertTrue(ctl.importSigned(auth,req,mp,order,claim,input)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(svc).importSigned(tenant,mp,order,claim,user,input);
    }
}
