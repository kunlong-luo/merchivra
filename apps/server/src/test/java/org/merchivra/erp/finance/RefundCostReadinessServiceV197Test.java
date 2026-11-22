package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Service wiring regression: a V63 probe happens after the V170 source
 * snapshot commits. A changed order, event, signed refund or currency
 * cannot be displayed as the original snapshot's current COGS sensitivity.
 */
class RefundCostReadinessServiceV197Test {
    private static final UUID TENANT=UUID.fromString(
        "00000000-0000-4000-8000-000000000710");
    private static final UUID MP=UUID.fromString(
        "00000000-0000-4000-8000-000000000711");
    private static final UUID EVENT=UUID.fromString(
        "00000000-0000-4000-8000-000000000712");
    private static final UUID ORDER=UUID.fromString(
        "00000000-0000-4000-8000-000000000713");
    private static RefundCostReadinessService.Source source() {
        return new RefundCostReadinessService.Source(
            EVENT,OffsetDateTime.parse("2026-11-01T09:00:00Z"),
            "USD",new BigDecimal("-20.000000"),ORDER);
    }
    private static RefundRecoveryService.Status review(
        UUID event,UUID order,String currency,String signed) {
        var exposure=new RefundRecoveryEngine.Preview(
            "MANUAL_EVIDENCE_BALANCED_UNVERIFIED",currency,signed,signed,
            "0.000000","8.000000","8.000000",null,false,false,
            List.of(new RefundRecoveryEngine.Item("ITEM","SKU",1,
                "RESTOCKED_CLAIMED",signed,"8.000000","8.000000")));
        return new RefundRecoveryService.Status(
            event.toString(),order.toString(),"REVIEWED",1,
            "MANUAL_UNVERIFIED","MAKER","CHECKER",true,false,false,
            exposure);
    }
    private static RefundCostReadinessService service(
        RefundRecoveryService recovery) {
        return new RefundCostReadinessService(mock(JdbcTemplate.class),
            recovery,mock(PlatformTransactionManager.class));
    }

    @Test void matchingReviewedManualEvidenceCanShowSensitivityButNotReversal() {
        var recovery=mock(RefundRecoveryService.class);
        when(recovery.preview(TENANT,MP,EVENT)).thenReturn(
            review(EVENT,ORDER,"USD","-20.00"));
        var event=service(recovery).describe(TENANT,MP,source());
        assertEquals("REVIEWED",event.evidenceState());
        assertTrue(event.evidenceCurrent());
        assertEquals("8.000000",event.historicalCostExposure());
        assertEquals("8.000000",event.claimedRestockCostSensitivity());
        assertFalse(event.externalReceiptVerified());
        assertFalse(event.actualCogsReversalAllowed());
        assertNull(event.actualCogsReversal());
        assertTrue(event.cogsReversalBlockerCodes().contains(
            "G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED"));
    }

    @Test void changedSourceOrderCurrencyAmountOrEventHideSensitivityAndMarkStale() {
        var altered=List.of(
            review(EVENT,UUID.randomUUID(),"USD","-20"),
            review(EVENT,ORDER,"EUR","-20"),
            review(EVENT,ORDER,"USD","-21"),
            review(UUID.randomUUID(),ORDER,"USD","-20"));
        for(var candidate:altered) {
            var recovery=mock(RefundRecoveryService.class);
            when(recovery.preview(TENANT,MP,EVENT)).thenReturn(candidate);
            var result=service(recovery).describe(TENANT,MP,source());
            assertEquals("STALE",result.evidenceState());
            assertFalse(result.evidenceCurrent());
            assertEquals("REFUND_SOURCE_CHANGED_BETWEEN_SNAPSHOT_AND_REVIEW",
                result.evidenceGap());
            assertNull(result.historicalCostExposure());
            assertNull(result.claimedRestockCostSensitivity());
            assertFalse(result.actualCogsReversalAllowed());
            assertNotEquals(0,result.cogsReversalBlockerCodes().size());
        }
    }

    @Test void normalV63MissingReviewConflictDoesNotFailEntireReadinessPage() {
        var recovery=mock(RefundRecoveryService.class);
        when(recovery.preview(TENANT,MP,EVENT)).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,
                "No reviewed refund item and historical cost"));
        var event=service(recovery).describe(TENANT,MP,source());
        assertEquals("INCOMPLETE_EVIDENCE",event.evidenceState());
        assertNull(event.historicalCostExposure());
        assertNull(event.claimedRestockCostSensitivity());
        assertTrue(event.cogsReversalBlockerCodes().contains(
            "REFUND_ITEM_AMOUNT_OR_COST_EVIDENCE_UNAVAILABLE"));
        verify(recovery).preview(TENANT,MP,EVENT);
    }
}
