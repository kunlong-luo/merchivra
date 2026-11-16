package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsSignedReturnServiceTest {
    @Test void ed25519VerifiesExactCanonicalPayloadAndRejectsTampering() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String spki=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),event=UUID.randomUUID(),
            intake=UUID.randomUUID(),warehouse=UUID.randomUUID(),source=UUID.randomUUID();
        var date=OffsetDateTime.parse("2026-11-17T02:11:00Z");
        String payload=WmsSignedReturnService.canonical(tenant,mp,event,intake,
            warehouse,source,"EXT-PARCEL-20261117","LOCAL-RETURN-20261117",
            2,"UNKNOWN",date);
        assertTrue(payload.startsWith("MERCHIVRA_WMS_RETURN_V1\n"));
        var signer=Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String sig=Base64.getEncoder().encodeToString(signer.sign());
        assertTrue(WmsSignedReturnService.verify(spki,sig,payload));
        for(String changed:new String[]{
            payload.replace("LOCAL-RETURN-20261117","LOCAL-RETURN-20261118"),
            payload.replace("EXT-PARCEL-20261117","EXT-PARCEL-20261118"),
            payload.replace("\n2\nUNKNOWN","\n3\nUNKNOWN"),
            payload.replace("UNKNOWN","SELLABLE_CLAIMED"),
            payload.replace(event.toString(),UUID.randomUUID().toString()),
            payload.replace(warehouse.toString(),UUID.randomUUID().toString()),
            payload.replace(source.toString(),UUID.randomUUID().toString()),
            payload.replace(intake.toString(),UUID.randomUUID().toString())})
            assertFalse(WmsSignedReturnService.verify(spki,sig,changed));
        assertFalse(WmsSignedReturnService.verify(spki,
            Base64.getEncoder().encodeToString(new byte[64]),payload));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                WmsSignedReturnService.publicKey("NOT-A-REAL-PUBLIC-KEY")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                WmsSignedReturnService.canonical(tenant,mp,event,intake,
                    warehouse,source,"BAD SPACE","LOCAL-RETURN-20261117",2,
                    "UNKNOWN",date)).getStatusCode());
    }
    @Test void onlySafeSourceCodeAndFreshCompleteObservation() {
        WmsSignedReturnService.code("WMS_VENDOR_V1");
        for(String s:new String[]{"wms", "WITH SPACES","x","abc-2026"}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                    ()->WmsSignedReturnService.code(s)).getStatusCode());
        }
        WmsSignedReturnService.observed(OffsetDateTime.now().minusDays(5));
        assertThrows(ResponseStatusException.class,
            ()->WmsSignedReturnService.observed(OffsetDateTime.now().plusDays(5)));
        assertThrows(ResponseStatusException.class,
            ()->WmsSignedReturnService.observed(null));
        assertThrows(ResponseStatusException.class,
            ()->WmsSignedReturnService.decoded("invalid@@",128));
    }
    @Test void ownerSessionAndActorAlwaysDerivedByController() {
        var access=mock(TenantAccessService.class);
        var svc=mock(WmsSignedReturnService.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),
            wh=UUID.randomUUID(),source=UUID.randomUUID(),mp=UUID.randomUUID(),
            event=UUID.randomUUID(),intake=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new WmsSigningSourceController(access,svc);
        assertTrue(api.list(auth,request,wh).getHeaders().getCacheControl().contains("no-store"));
        verify(svc).sources(tenant,wh);
        var input=new WmsSignedReturnService.Register(wh,"WMS_VENDOR_V1",
            "unused-public-key","Distinct Owner must verify the source identity");
        api.register(auth,request,input);
        verify(svc).register(tenant,actor,input);
        var note=new WmsSignedReturnService.Decision(
            "Independently reviewed untrusted signing source key");
        api.approve(auth,request,source,note);
        verify(svc).decide(tenant,actor,source,true,note.reason());
        api.revoke(auth,request,source,note);
        verify(svc).decide(tenant,actor,source,false,note.reason());
        var receipts=new WmsSignedReturnController(access,svc);
        assertTrue(receipts.list(auth,request,mp,event,intake)
            .getHeaders().getCacheControl().contains("no-store"));
        var signed=new WmsSignedReturnService.SignedInput(source,
            "EXT-PARCEL-20261117",OffsetDateTime.now(),1,"UNKNOWN","signature");
        receipts.create(auth,request,mp,event,intake,signed);
        verify(svc).importReceipt(tenant,mp,event,intake,actor,signed);
    }
    @Test void databaseEnsuresSignatureCannotBecomeUnreviewedPhysicalStock() throws Exception {
        String ddl=Files.readString(Path.of(
            "src/main/resources/db/migration/V98__wms_signed_return_provenance.sql"));
        for(String s:new String[]{
            "UNIQUE(tenant_id,source_id,external_receipt_id)",
            "UNIQUE(tenant_id,intake_id)",
            "finance_customer_return_intake_wh_v98",
            "FOREIGN KEY(tenant_id,intake_id,warehouse_id)",
            "operator_identity_verified=FALSE","physical_receipt_verified=FALSE",
            "CHECK(inventory_posted=FALSE)","CHECK(cogs_posted=FALSE)",
            "BEFORE UPDATE OR DELETE ON finance_wms_signed_return_v98",
            "BEFORE UPDATE OR DELETE ON finance_wms_signing_source_v98"})
            assertTrue(ddl.contains(s),s);
        String impl=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/WmsSignedReturnService.java"));
        for(String unsafe:new String[]{
            "INSERT INTO erp_local_stock_movement","UPDATE erp_local_stock_movement",
            "INSERT INTO finance_profit_report","UPDATE finance_profit_report",
            "INSERT INTO finance_ledger","UPDATE finance_source_event"})
            assertFalse(impl.contains(unsafe),unsafe);
        assertTrue(impl.contains("Signature.getInstance(\"Ed25519\")"));
        assertTrue(impl.contains("TransactionDefinition.ISOLATION_REPEATABLE_READ"));
    }
}
