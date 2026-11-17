package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsReturnQcReviewServiceTest {
    @Test void qcCanDisagreeWithSignedObservationWithoutCertification() {
        assertEquals("QC_MATCHED_UNVERIFIED",WmsReturnQcReviewService.qcComparison(
            4,4,"UNKNOWN","UNKNOWN"));
        assertEquals("QC_QUANTITY_MISMATCH",WmsReturnQcReviewService.qcComparison(
            4,3,"UNKNOWN","UNKNOWN"));
        assertEquals("QC_CONDITION_MISMATCH",WmsReturnQcReviewService.qcComparison(
            4,4,"UNKNOWN","UNSELLABLE_CLAIMED"));
        assertEquals("QC_BOTH_MISMATCH",WmsReturnQcReviewService.qcComparison(
            4,3,"UNKNOWN","UNSELLABLE_CLAIMED"));
        var good=new WmsReturnQcReviewService.Request(3,"UNKNOWN",
            "QC-MANUAL-REFERENCE-001","Two parcels independently counted");
        assertDoesNotThrow(()->WmsReturnQcReviewService.validate(good));
        for(var invalid:new WmsReturnQcReviewService.Request[]{
            new WmsReturnQcReviewService.Request(0,"UNKNOWN",
                "QC-MANUAL-REFERENCE-001","Two parcels independently counted"),
            new WmsReturnQcReviewService.Request(2,"SELLABLE",
                "QC-MANUAL-REFERENCE-001","Two parcels independently counted"),
            new WmsReturnQcReviewService.Request(2,"UNKNOWN","BAD",
                "Two parcels independently counted"),
            new WmsReturnQcReviewService.Request(2,"UNKNOWN",
                "QC-MANUAL-REFERENCE-001","short")
        }) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
                ResponseStatusException.class,
                ()->WmsReturnQcReviewService.validate(invalid)).getStatusCode());
        }
    }
    @Test void ownerSessionSuppliesTenantAndActorAndResponsesAreNeverCached() {
        var access=mock(TenantAccessService.class);
        var service=mock(WmsReturnQcReviewService.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),
            mp=UUID.randomUUID(),event=UUID.randomUUID(),
            intake=UUID.randomUUID(),id=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new WmsReturnQcReviewController(access,service);
        assertTrue(api.get(auth,request,mp,event,intake)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).report(tenant,mp,event,intake);
        var body=new WmsReturnQcReviewService.Request(2,"UNKNOWN",
            "QC-MANUAL-REFERENCE-001","Independently counted at local warehouse");
        assertTrue(api.create(auth,request,mp,event,intake,body)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).request(tenant,mp,event,intake,actor,body);
        var decision=new WmsReturnQcReviewService.Decision(
            "Acknowledged receipt of QC observation only");
        assertTrue(api.acknowledge(auth,request,mp,event,intake,id,decision)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).decide(tenant,mp,event,intake,id,actor,true,decision);
        api.reject(auth,request,mp,event,intake,id,decision);
        verify(service).decide(tenant,mp,event,intake,id,actor,false,decision);
    }
    @Test void flywayEnforcesScopedImmutableThreeActorSeparationAndNoStockCogs()
        throws Exception {
        String ddl=Files.readString(Path.of(
            "src/main/resources/db/migration/V100__wms_return_qc_review.sql"));
        for(String required:new String[]{
            "finance_wms_observation_qc_scope_v100",
            "finance_wms_qc_case_v100",
            "finance_wms_qc_decision_v100",
            "UNIQUE(tenant_id,observation_id)",
            "UNIQUE(tenant_id,case_id)",
            "finance_wms_qc_actor_guard_v100",
            "signed_importer=NEW.requested_by",
            "NEW.decided_by IN (qc_requester,signed_importer)",
            "BEFORE UPDATE OR DELETE ON finance_wms_qc_case_v100",
            "BEFORE UPDATE OR DELETE ON finance_wms_qc_decision_v100",
            "CHECK(operator_identity_verified=FALSE)",
            "CHECK(physical_receipt_verified=FALSE)",
            "CHECK(inventory_posted=FALSE)",
            "CHECK(cogs_reversed=FALSE)",
            "finance_wms_append_only_v98()"})
            assertTrue(ddl.contains(required),required);
        String service=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/WmsReturnQcReviewService.java"));
        for(String unsafe:new String[]{"INSERT INTO erp_local_stock_movement",
            "INSERT INTO finance_profit_report","UPDATE finance_source_event",
            "INSERT INTO finance_cogs"})
            assertFalse(service.contains(unsafe),unsafe);
        assertTrue(service.contains("TransactionDefinition.ISOLATION_REPEATABLE_READ"));
        assertTrue(service.contains("RefundRecoveryService.requireIndependent"));
        assertTrue(service.contains("STALE_LOCAL_HOLD"));
        assertTrue(service.contains("REVOKED_SOURCE_HOLD"));
    }
}
