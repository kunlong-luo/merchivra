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

class WmsReturnReconciliationServiceTest {
    @Test void allDiscrepancyKindsStayQuarantined() {
        assertEquals("MATCHED_UNVERIFIED",
            WmsReturnReconciliationService.classify(3,3,"UNKNOWN","UNKNOWN"));
        assertEquals("QUANTITY_MISMATCH",
            WmsReturnReconciliationService.classify(3,2,"UNKNOWN","UNKNOWN"));
        assertEquals("CONDITION_MISMATCH",
            WmsReturnReconciliationService.classify(3,3,"UNKNOWN","SELLABLE_CLAIMED"));
        assertEquals("BOTH_MISMATCH",
            WmsReturnReconciliationService.classify(3,2,"UNKNOWN","UNSELLABLE_CLAIMED"));
    }
    @Test void v2DetachedEd25519SignatureBindsLocalAndObservedAndIdentity() throws Exception {
        var kp=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var publicSpki=Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),event=UUID.randomUUID(),
            intake=UUID.randomUUID(),warehouse=UUID.randomUUID(),source=UUID.randomUUID();
        var date=OffsetDateTime.parse("2026-11-17T01:30:00Z");
        String payload=WmsReturnReconciliationService.canonical(tenant,mp,event,
            intake,warehouse,source,"EXTERNAL-SIGNED-OBS-20261117",
            "LOCAL-PARCEL-20261117",4,"UNKNOWN",2,"UNSELLABLE_CLAIMED",date);
        assertTrue(payload.startsWith("MERCHIVRA_WMS_OBSERVATION_V2\n"));
        assertTrue(payload.endsWith("2026-11-17T01:30:00Z\n"));
        Signature signer=Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String sig=Base64.getEncoder().encodeToString(signer.sign());
        assertTrue(WmsSignedReturnService.verify(publicSpki,sig,payload));
        for(String edited:new String[]{
            payload.replace("LOCAL-PARCEL-20261117","LOCAL-PARCEL-20261118"),
            payload.replace("EXTERNAL-SIGNED-OBS-20261117","EXTERNAL-SIGNED-OBS-20261118"),
            payload.replace("\n4\nUNKNOWN","\n5\nUNKNOWN"),
            payload.replace("\n2\nUNSELLABLE_CLAIMED","\n1\nUNSELLABLE_CLAIMED"),
            payload.replace("UNSELLABLE_CLAIMED","SELLABLE_CLAIMED"),
            payload.replace(warehouse.toString(),UUID.randomUUID().toString()),
            payload.replace(tenant.toString(),UUID.randomUUID().toString()),
            payload.replace(mp.toString(),UUID.randomUUID().toString()),
            payload.replace(event.toString(),UUID.randomUUID().toString()),
            payload.replace(source.toString(),UUID.randomUUID().toString()),
            payload.replace(intake.toString(),UUID.randomUUID().toString())}) {
            assertFalse(WmsSignedReturnService.verify(publicSpki,sig,edited));
        }
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                WmsReturnReconciliationService.canonical(tenant,mp,event,
                    intake,warehouse,source,"EXT BAD","LOCAL-PARCEL-20261117",
                    4,"UNKNOWN",2,"UNKNOWN",date)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                WmsReturnReconciliationService.canonical(tenant,mp,event,
                    intake,warehouse,source,"EXTERNAL-SIGNED-OBS-20261117",
                    "LOCAL-PARCEL-20261117",4,"UNKNOWN",0,"UNKNOWN",date)).getStatusCode());
    }
    @Test void tenantAndActorAlwaysComeFromAuthenticatedSession() {
        var access=mock(TenantAccessService.class);
        var service=mock(WmsReturnReconciliationService.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var tenant=UUID.randomUUID();var actor=UUID.randomUUID();
        var mp=UUID.randomUUID();var event=UUID.randomUUID();var intake=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new WmsReturnReconciliationController(access,service);
        assertTrue(api.get(auth,request,mp,event,intake)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).report(tenant,mp,event,intake);
        var data=new WmsReturnReconciliationService.ObservationInput(UUID.randomUUID(),
            "SIGNED-EXT-RETURN-20261117",OffsetDateTime.now(),1,"UNKNOWN","signature");
        assertTrue(api.record(auth,request,mp,event,intake,data)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).record(tenant,mp,event,intake,actor,data);
    }
    @Test void pg18ConstrainsAcrossBothV173AndV174AndForbidsFinancialPromotion()
        throws Exception {
        String ddl=Files.readString(Path.of(
            "src/main/resources/db/migration/V99__signed_wms_return_discrepancy.sql"));
        for(String s:new String[]{
            "PRIMARY KEY (tenant_id,warehouse_id,external_receipt_id)",
            "UNIQUE (tenant_id,intake_id)",
            "INSERT INTO finance_wms_receipt_identity_v99",
            "AFTER INSERT ON finance_wms_signed_return_v98",
            "AFTER INSERT ON finance_wms_return_observation_v99",
            "finance_customer_return_intake_event_scoped_v99",
            "FOREIGN KEY (tenant_id,intake_id,seller_marketplace_id,event_id,warehouse_id)",
            "physical_receipt_verified=FALSE",
            "operator_identity_verified=FALSE",
            "CHECK(inventory_posted=FALSE)",
            "CHECK(cogs_reversed=FALSE)",
            "BEFORE UPDATE OR DELETE ON finance_wms_return_observation_v99",
            "BEFORE UPDATE OR DELETE ON finance_wms_receipt_identity_v99"})
            assertTrue(ddl.contains(s),s);
        String impl=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/WmsReturnReconciliationService.java"));
        for(String unsafe:new String[]{
            "INSERT INTO erp_local_stock_movement",
            "INSERT INTO finance_profit_report",
            "UPDATE finance_source_event",
            "INSERT INTO finance_cogs"})
            assertFalse(impl.contains(unsafe),unsafe);
        assertTrue(impl.contains("TransactionDefinition.ISOLATION_REPEATABLE_READ"));
        assertTrue(impl.contains("WmsSignedReturnService.verify"));
        assertTrue(impl.contains("WMS receipt already reserved by V173 or V174"));
    }
}
