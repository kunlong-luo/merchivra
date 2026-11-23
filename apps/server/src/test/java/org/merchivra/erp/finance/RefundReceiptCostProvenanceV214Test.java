package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Contract tests: V91/V92 review is not receipt allocation or real COGS. */
class RefundReceiptCostProvenanceV214Test {
    private static final UUID MP = UUID.fromString("00000000-0000-4000-8000-000000000214");
    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-8000-000000000215");

    private static RefundGlobalReceiptCapacityGateV213.Result parent() {
        var result = mock(RefundGlobalReceiptCapacityGateV213.Result.class);
        when(result.marketplaceId()).thenReturn(MP.toString());
        when(result.orderId()).thenReturn(ORDER.toString());
        when(result.originalReceiptCount()).thenReturn(2);
        when(result.blockerCodes()).thenReturn(List.of("EXTERNAL_AUDIT_MISSING"));
        return result;
    }

    private static RefundReceiptCostProvenanceGateV214.ManualSource source(
        int id, String category, String review, String allocation) {
        return new RefundReceiptCostProvenanceGateV214.ManualSource(
            UUID.fromString("00000000-0000-4000-8000-" +
                String.format("%012d", id)), category, review,
            allocation == null ? null : UUID.fromString(
                "10000000-0000-4000-8000-" + String.format("%012d", id)),
            allocation, allocation == null ? null : "ORDER");
    }

    @Test void approvedManualMarketplaceFeesDoNotBecomeReceiptLandedCogs() {
        var output = RefundReceiptCostProvenanceGateV214.evaluate(MP, ORDER,
            parent(), List.of(
                source(1, "INBOUND_FREIGHT", "REVIEWED_UNVERIFIED",
                    "APPROVED_UNVERIFIED"),
                source(2, "IMPORT_DUTY", "REVIEWED_UNVERIFIED",
                    "PENDING_REVIEW"),
                source(3, "PAYMENT_AND_FX", "STAGED", null)));
        assertEquals(2, output.originalReceiptCount());
        assertEquals(3, output.marketplaceManualCandidateSourceCount());
        assertEquals(2, output.locallyReviewedUnverifiedCandidateCount());
        assertEquals(2, output.activeManualAllocationCount());
        assertEquals(1, output.approvedUnverifiedAllocationCount());
        assertEquals(3, output.categories().size());
        assertEquals(1, output.categories().getFirst().approvedUnverifiedAllocationCount());
        assertTrue(output.blockerCodes().contains(
            "V91_V92_COST_SOURCE_HAS_NO_PURCHASE_RECEIPT_IDENTITY"));
        assertTrue(output.blockerCodes().contains(
            "V92_APPROVED_UNVERIFIED_IS_NOT_RECEIPT_COST_PROOF"));
        assertFalse(output.v91V92PurchaseReceiptIdentityLinked());
        assertFalse(output.externalFreightDutyAndFxVerified());
        assertFalse(output.verifiedLandedReceiptCostReady());
        assertNull(output.verifiedLandedReceiptCost());
        assertFalse(output.refundCogsReversalAllowed());
        assertNull(output.actualRefundCogsReversal());
        assertNull(output.realizedNetProfit());
    }

    @Test void absentLocalMetadataIsUnknownNotZeroLandedCost() {
        var output = RefundReceiptCostProvenanceGateV214.evaluate(
            MP, ORDER, parent(), List.of());
        assertEquals(0, output.marketplaceManualCandidateSourceCount());
        assertTrue(output.blockerCodes().contains(
            "NO_MANUAL_LANDED_COST_CANDIDATE_METADATA_NOT_ZERO_COST"));
        assertNull(output.verifiedLandedReceiptCost());
        assertNull(output.realizedNetProfit());
    }

    @Test void malformedOrDuplicatedSourceAndAllocationFailClosed() {
        var a = source(1, "IMPORT_DUTY", "REVIEWED_UNVERIFIED", null);
        assertThrows(IllegalArgumentException.class, () ->
            RefundReceiptCostProvenanceGateV214.evaluate(MP, ORDER,
                parent(), List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () ->
            RefundReceiptCostProvenanceGateV214.evaluate(MP, ORDER,
                parent(), List.of(source(2, "ADS_SPEND", "STAGED", null))));
        assertThrows(IllegalArgumentException.class, () ->
            RefundReceiptCostProvenanceGateV214.evaluate(MP, ORDER,
                parent(), List.of(new RefundReceiptCostProvenanceGateV214.ManualSource(
                    a.id(), a.category(), a.reviewState(), null,
                    "APPROVED_UNVERIFIED", "ORDER"))));
        var stale = parent();
        when(stale.realizedNetProfit()).thenReturn("10.00");
        assertThrows(IllegalArgumentException.class, () ->
            RefundReceiptCostProvenanceGateV214.evaluate(MP, ORDER,
                stale, List.of()));
    }

    @Test void queryIsOnlyMarketplaceCandidateMetadataNeverReceiptOrFinancialJoin() {
        String sql = RefundReceiptCostProvenanceServiceV214.MARKETPLACE_CANDIDATE_SQL;
        assertTrue(sql.contains("FROM finance_cost_source_v91 s"));
        assertTrue(sql.contains("LEFT JOIN finance_cost_allocation_batch_v92 b"));
        assertTrue(sql.contains("s.tenant_id=? AND s.seller_marketplace_id=?"));
        assertTrue(sql.contains("LIMIT 1001"));
        assertFalse(sql.contains("erp_purchase_receipt"));
        assertFalse(sql.contains("finance_sale_lot_claim_v106"));
        assertFalse(sql.contains("expense_amount"));
    }

    @Test void ownerScopedControllerDisablesCachingAndDoesNotWriteCosts() {
        var access = mock(TenantAccessService.class);
        var service = mock(RefundReceiptCostProvenanceServiceV214.class);
        var authentication = mock(Authentication.class);
        var request = mock(HttpServletRequest.class);
        var tenant = UUID.fromString("00000000-0000-4000-8000-000000000216");
        when(access.currentTenant(authentication, request)).thenReturn(tenant);
        var response = new RefundReceiptCostProvenanceControllerV214(
            access, service).preview(authentication, request, MP, ORDER);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant, MP, ORDER);
    }
}
