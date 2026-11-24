package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V229: local audit can be intact but EXTERNAL proof is always absent. */
class CostSourceAuditIntegrityV229Test {
    private static final UUID SOURCE=UUID.randomUUID(), MP=UUID.randomUUID(),
        MAKER=UUID.randomUUID(), CHECKER=UUID.randomUUID();

    private static CostSourceAuditIntegrityServiceV229.Evidence examine(
        String state,UUID checker,long all,long staged,long decision) {
        return CostSourceAuditIntegrityServiceV229.evaluate(SOURCE,MP,
            state,MAKER,checker,new CostSourceAuditIntegrityServiceV229.Counts(
                all,staged,decision));
    }
    private static void noExternalPromotion(
        CostSourceAuditIntegrityServiceV229.Evidence e) {
        assertFalse(e.claimedDocumentBytesVerified());
        assertFalse(e.independentExternalIssuerVerified());
        assertFalse(e.externalChargeVerified());
        assertFalse(e.actualCogsVerified());
        assertFalse(e.eligibleForRealizedProfit());
        assertNull(e.realizedNetProfit());
        assertTrue(e.blockerCodes().contains(
            "INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED"));
        assertTrue(e.blockerCodes().contains(
            "CLAIMED_DOCUMENT_SHA256_NOT_BYTES_VERIFIED"));
    }
    @Test void exactOneStageAuditIsLocallyCompleteButNeverVerified() {
        var e=examine("STAGED",null,1,1,0);
        assertTrue(e.localAuditComplete());
        assertTrue(e.stageAuditIntact());
        assertFalse(e.independentDecisionAuditIntact());
        assertTrue(e.blockerCodes().contains(
            "V91_INDEPENDENT_METADATA_REVIEW_PENDING"));
        noExternalPromotion(e);
    }
    @Test void independentOneShotReviewedAndRejectedAuditAreLocallyComplete() {
        for(String state:List.of("REVIEWED_UNVERIFIED","REJECTED")) {
            var e=examine(state,CHECKER,2,1,1);
            assertTrue(e.localAuditComplete());
            assertTrue(e.independentDecisionAuditIntact());
            if(state.equals("REJECTED"))
                assertTrue(e.blockerCodes().contains("V91_SOURCE_REJECTED"));
            noExternalPromotion(e);
        }
    }
    @Test void noInitialAuditDuplicateOrWrongMakerFailsClosed() {
        for(var counts:List.of(
            new long[]{0,0,0},new long[]{1,0,0},
            new long[]{2,2,0},new long[]{2,1,0})) {
            var e=examine("STAGED",null,counts[0],counts[1],counts[2]);
            assertFalse(e.localAuditComplete());
            assertTrue(e.blockerCodes().contains("V91_SOURCE_AUDIT_INCONSISTENT"));
            noExternalPromotion(e);
        }
    }
    @Test void missingDuplicateSelfOrUnmatchedDecisionIsNeverAuditedApproval() {
        var one=examine("REVIEWED_UNVERIFIED",CHECKER,1,1,0);
        var two=examine("REVIEWED_UNVERIFIED",MAKER,2,1,1);
        var three=examine("REVIEWED_UNVERIFIED",CHECKER,3,1,1);
        var four=examine("REVIEWED_UNVERIFIED",CHECKER,3,1,2);
        var five=examine("REVIEWED_UNVERIFIED",null,2,1,0);
        for(var e:List.of(one,two,three,four,five)) {
            assertFalse(e.localAuditComplete());
            assertTrue(e.blockerCodes().contains("V91_SOURCE_AUDIT_INCONSISTENT"));
            noExternalPromotion(e);
        }
        assertTrue(one.blockerCodes().contains(
            "V91_INDEPENDENT_DECISION_AUDIT_MISSING_OR_MISMATCHED"));
        assertTrue(two.blockerCodes().contains(
            "V91_INDEPENDENT_DECISION_AUDIT_MISSING_OR_MISMATCHED"));
    }
    @Test void sessionTenantNoCacheAndOnlyScopedSourceRead() throws Exception {
        var access=mock(TenantAccessService.class);
        var service=mock(CostSourceAuditIntegrityServiceV229.class);
        var request=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=new CostSourceAuditIntegrityControllerV229(access,service)
            .get(auth,request,MP,SOURCE);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect(tenant,MP,SOURCE);
        verifyNoMoreInteractions(service);
        String sql=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostSourceAuditIntegrityServiceV229.java"));
        assertTrue(sql.contains("Isolation.REPEATABLE_READ"));
        assertTrue(sql.contains("s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?"));
        assertTrue(sql.contains("finance_cost_source_audit_v91 a"));
        assertTrue(sql.contains("AND a.actor_id=s.reviewed_by"));
        assertFalse(sql.contains("INSERT INTO finance_"));
        assertFalse(sql.contains("UPDATE finance_"));
        assertFalse(sql.contains("DELETE FROM finance_"));
    }
    @Test void missingOrForeignSourceDoesNotReturnEvidence() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CostSourceAuditIntegrityServiceV229(jdbc);
        var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->service.inspect(null,MP,SOURCE));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
