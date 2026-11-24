package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V216: reviewed local metadata and V165 no-match are still not actual COGS. */
class ReceiptCostIntegrityV216Test {
    static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012x",n));
    }
    static final UUID MARKETPLACE=id(2160), RECEIPT=id(2161), SOURCE=id(2162);
    static final UUID PLAN=id(2163);

    static ReceiptCostIntegrityGateV216.Source row() {
        return new ReceiptCostIntegrityGateV216.Source(PLAN,SOURCE,
            "APPROVED_UNVERIFIED","REVIEWED_UNVERIFIED","USD","INBOUND_FREIGHT",
            new BigDecimal("100.000000"),"USD",new BigDecimal("100.000000"),
            new BigDecimal("35.000000"),new BigDecimal("95.000000"),
            2,2,2,0,true,true,true);
    }
    static CostIdentityReconciliationService.Preview preview(boolean collide) {
        return CostIdentityReconciliationService.summarize(SOURCE,
            "REVIEWED_UNVERIFIED",List.of(collide,false,false,false,
                false,false,false,false));
    }
    static ReceiptCostIntegrityGateV216.Result inspect(
        ReceiptCostIntegrityGateV216.Source source,
        CostIdentityReconciliationService.Preview cross) {
        return ReceiptCostIntegrityGateV216.evaluate(MARKETPLACE,RECEIPT,
            List.of(source),Map.of(SOURCE,cross));
    }

    @Test void approvedInternalManualCostIsNeverVerifiedLandedCost() {
        var value=inspect(row(),preview(false));
        assertEquals(1,value.localPlanCount());
        assertEquals("LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED",
            value.plans().getFirst().integrityState());
        assertEquals("35.000000",
            value.plans().getFirst().localReceiptCandidateAmount());
        assertEquals("95.000000",
            value.plans().getFirst().localAllReceiptsCandidateAmount());
        assertFalse(value.completeExternalCostCoverage());
        assertFalse(value.receiptLandedCostVerified());
        assertFalse(value.actualBatchCogsVerified());
        assertNull(value.actualBatchCogs());
        assertFalse(value.postingAllowed());
        assertNull(value.realizedNetProfit());
        assertFalse(value.plans().getFirst().independentlyVerifiedLandedCost());
        assertNull(value.plans().getFirst().actualLandedCost());
        assertTrue(value.blockerCodes().contains(
            "V112_LOCAL_MANUAL_PROPOSAL_IS_NOT_EXTERNAL_LANDED_COST"));
    }

    @Test void collidedSourceIsPotentialOverlapNotVerifiedDuplicateDebit() {
        var value=inspect(row(),preview(true));
        assertEquals("POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD",
            value.plans().getFirst().integrityState());
        assertTrue(value.blockerCodes().contains("V165_CROSS_SOURCE_POTENTIAL_COLLISION_HOLD"));
        assertEquals(1,value.plans().getFirst().crossSourceIdentitySignalCount());
        assertNull(value.actualBatchCogs());
    }

    @Test void changedSourceCurrencyAmountsV92OverlapAndAuditStaleFailClosed() {
        var original=row();
        var stale=new ReceiptCostIntegrityGateV216.Source(
            original.planId(),original.sourceId(),original.state(),
            original.sourceReviewState(),"EUR",original.sourceCategory(),
            original.sourceAmount(),original.snapshotCurrency(),
            original.snapshotAmount(),original.receiptAmount(),
            new BigDecimal("110.000000"),2,1,2,1,true,false,false);
        var result=inspect(stale,preview(false));
        assertEquals("LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED",
            result.plans().getFirst().integrityState());
        assertTrue(result.blockerCodes().contains(
            "RECEIPT_LOCAL_SOURCE_AMOUNT_CURRENCY_OR_V92_DRIFT"));
        assertTrue(result.blockerCodes().contains(
            "SOURCE_OR_INDEPENDENT_DECISION_AUDIT_MISSING"));
        assertNull(result.realizedNetProfit());
    }

    @Test void missingPlanIsUnknownNotZeroAndPendingRemainsUnverified() {
        var empty=ReceiptCostIntegrityGateV216.evaluate(MARKETPLACE,
            RECEIPT,List.of(),Map.of());
        assertTrue(empty.blockerCodes().contains(
            "NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO"));
        assertNull(empty.actualBatchCogs());
        var p=row();
        var pending=new ReceiptCostIntegrityGateV216.Source(p.planId(),p.sourceId(),
            "PENDING_REVIEW",p.sourceReviewState(),p.sourceCurrency(),
            p.sourceCategory(),p.sourceAmount(),p.snapshotCurrency(),
            p.snapshotAmount(),p.receiptAmount(),p.allReceiptsAmount(),
            p.lineCount(),p.sameCurrencyLineCount(),p.currentReceiptCount(),
            0,true,false,true);
        assertEquals("PENDING_LOCAL_REVIEW",inspect(pending,preview(false))
            .plans().getFirst().integrityState());
    }

    @Test void duplicatePlansOrSourceStateDriftCannotProduceValidRead() {
        var s=row();
        assertThrows(IllegalArgumentException.class,()->
            ReceiptCostIntegrityGateV216.evaluate(MARKETPLACE,RECEIPT,
                List.of(s,s),Map.of(SOURCE,preview(false))));
        assertThrows(IllegalArgumentException.class,()->
            ReceiptCostIntegrityGateV216.evaluate(MARKETPLACE,RECEIPT,
                List.of(s),Map.of()));
        var another=CostIdentityReconciliationService.summarize(SOURCE,
            "STAGED",List.of(false,false,false,false,false,false,false,false));
        assertThrows(IllegalArgumentException.class,()->
            inspect(s,another));
    }

    @Test void scopeSqlScansWholePlanWithoutPaginationOrAcrossMarketplaceMixing() {
        var sql=ReceiptCostIntegrityServiceV216.RECEIPT_SCOPE_SQL;
        assertTrue(sql.contains("finance_receipt_cost_line_v112 all_lines"));
        assertTrue(sql.contains("all_lines.plan_id=p.id"));
        assertTrue(sql.contains("s.seller_marketplace_id=?"));
        assertTrue(sql.contains("l.tenant_id=? AND l.receipt_id=?"));
        assertTrue(sql.contains("finance_cost_source_audit_v91 a"));
        assertTrue(sql.contains("finance_receipt_cost_audit_v112 a"));
        assertTrue(sql.contains("finance_cost_allocation_batch_v92 v92"));
        assertTrue(sql.contains("ORDER BY p.id LIMIT 51"));
        assertFalse(sql.contains("OFFSET"));
    }

    @Test void onlyOwnerScopedSessionGetAndNoStore() {
        var access=mock(TenantAccessService.class);
        var service=mock(ReceiptCostIntegrityServiceV216.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var tenant=id(2164);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=new ReceiptCostIntegrityControllerV216(access,service)
            .preview(auth,request,MARKETPLACE,RECEIPT);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,MARKETPLACE,RECEIPT);
    }
}
