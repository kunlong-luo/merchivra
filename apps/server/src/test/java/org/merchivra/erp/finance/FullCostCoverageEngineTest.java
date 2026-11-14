package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.merchivra.erp.finance.FullCostCoverageEngine.*;

class FullCostCoverageEngineTest {
    private static OrderContributionEngine.Preview base() {
        var cost = OrderCostEngine.evaluate(true, true,
            Instant.parse("2026-11-01T00:00:00Z"),"USD",
            List.of(new OrderCostEngine.Item("line-1","seller-sku",2,
                "LIVE","internal-sku",new BigDecimal("4.00"),"USD")));
        return OrderContributionEngine.calculate("USD",cost,List.of(
            new OrderContributionEngine.Transaction("sale","RECONCILED_UNVERIFIED",
                List.of(new OrderContributionEngine.CategoryAmount(
                    "PRODUCT_SALES","USD",new BigDecimal("20.00")))),
            new OrderContributionEngine.Transaction("referral","RECONCILED_UNVERIFIED",
                List.of(new OrderContributionEngine.CategoryAmount(
                    "AMAZON_FEE","USD",new BigDecimal("-3.00")))),
            new OrderContributionEngine.Transaction("fulfillment","RECONCILED_UNVERIFIED",
                List.of(new OrderContributionEngine.CategoryAmount(
                    "FBA_FEE","USD",new BigDecimal("-2.00"))))
        ),0,false);
    }
    private static Evidence fee(Category category, String value, String sourceId) {
        return new Evidence(category,State.REVIEWED_COST,"USD",
            new BigDecimal(value),"INVOICE",sourceId,true);
    }
    private static Evidence unverified(Category category,String amount) {
        return new Evidence(category,State.UNVERIFIED_COST,"USD",
            new BigDecimal(amount),"MANUAL","manual-"+category.name(),false);
    }
    private static Evidence zero(Category category) {
        return new Evidence(category,State.REVIEWED_ZERO,"USD",BigDecimal.ZERO,
            "INVOICE","zero-"+category.name(),true);
    }
    private static Evidence notApplicable(Category category) {
        return new Evidence(category,State.REVIEWED_NOT_APPLICABLE,"USD",null,
            "INVOICE","na-"+category.name(),true);
    }

    @Test void knownExpenseWaterfallReusesExistingSignedV33BaseWithoutDoubleCounting() {
        var result = calculate(base(),List.of(
            fee(Category.ADS_SPEND,"1.250000","ads-1"),
            fee(Category.INBOUND_FREIGHT,"0.50","freight-1")));
        assertEquals("COST_COVERAGE_PARTIAL_PREVIEW",result.state());
        assertEquals("USD",result.currency());
        assertEquals("7.00",result.baselineContribution());
        assertEquals("1.750000",result.additionalReviewedExpense());
        assertEquals("5.250000",result.knownCostAdjustedContribution());
        assertEquals(2,result.coveredCategories());
        assertEquals(8,result.totalCategories());
        assertEquals(6,result.missingCategories().size());
        assertEquals(Category.FBA_STORAGE,result.missingCategories().getFirst());
        assertFalse(result.fullCostSourceComplete());
        assertFalse(result.bankReconciled());
        assertFalse(result.ledgerPosted());
        assertFalse(result.realizedNetProfitVerified());
        assertNull(result.realizedNetProfit());
    }

    @Test void everyRequiredCategoryPresentStillDoesNotManufactureRealizedProfit() {
        var all = new ArrayList<Evidence>();
        for (Category category:Category.values())
            all.add(category == Category.ADS_SPEND
                ? fee(category,"1.00","ads-1")
                : category == Category.FBM_SHIPPING ? notApplicable(category)
                : zero(category));
        var result=calculate(base(),all);
        assertEquals("ALL_CATEGORIES_REFERENCED_PREVIEW_ONLY",result.state());
        assertEquals(8,result.coveredCategories());
        assertTrue(result.missingCategories().isEmpty());
        assertEquals("6.00",result.knownCostAdjustedContribution());
        assertFalse(result.fullCostSourceComplete());
        assertFalse(result.realizedNetProfitVerified());
        assertNull(result.realizedNetProfit());
    }

    @Test void unknownAndManualUnreviewedAreNeverTreatedAsZeroOrDeducted() {
        var result=calculate(base(),List.of(
            new Evidence(Category.ADS_SPEND,State.UNKNOWN,null,null,null,null,false),
            unverified(Category.FBA_STORAGE,"5.25")));
        assertEquals("7.00",result.baselineContribution());
        assertEquals("0",result.additionalReviewedExpense());
        assertEquals("7.00",result.knownCostAdjustedContribution());
        assertTrue(result.missingCategories().contains(Category.ADS_SPEND));
        assertTrue(result.missingCategories().contains(Category.FBA_STORAGE));
        var storage=result.categories().stream()
            .filter(e->e.category()==Category.FBA_STORAGE).findFirst().orElseThrow();
        assertEquals("UNVERIFIED_COST",storage.state());
        assertNull(storage.knownExpense());
        assertFalse(storage.includedInPreview());
        assertEquals(0,result.coveredCategories());
    }

    @Test void rejectDuplicateCostCategoriesAndDuplicateRawEvidenceAcrossCategories() {
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            fee(Category.ADS_SPEND,"1","same-1"),
            fee(Category.ADS_SPEND,"2","different-2"))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            fee(Category.ADS_SPEND,"1","shared"),
            fee(Category.FBA_STORAGE,"2","shared"))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.ADS_SPEND,State.REVIEWED_COST,"USD",BigDecimal.ONE,
                "FINANCES","maybe-already-in-v33",true))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.ADS_SPEND,State.REVIEWED_COST,"USD",BigDecimal.ONE,
                "SETTLEMENT","same-finances-statement",true))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.ADS_SPEND,State.REVIEWED_COST,"USD",BigDecimal.ONE,
                "ORDERS","provisional-gross",true))));
    }

    @Test void crossCurrencyAndNegativeCostFailClosedBeforeSubtracting() {
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.ADS_SPEND,State.REVIEWED_COST,"EUR",BigDecimal.ONE,
                "ADS_REPORT","ads-eur",true))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            fee(Category.ADS_SPEND,"-1.50","credit-requires-separate-refund"))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            fee(Category.ADS_SPEND,"0","zero-must-be-explicit"))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            fee(Category.ADS_SPEND,"1.1234567","too-precise"))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            fee(Category.ADS_SPEND,"100000000000000","too-large"))));
    }

    @Test void unapprovedOrFakeZeroAndNotApplicableCannotPassReview() {
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.ADS_SPEND,State.REVIEWED_COST,"USD",BigDecimal.ONE,
                "ADS_REPORT","ads-1",false))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.FBM_SHIPPING,State.REVIEWED_NOT_APPLICABLE,
                "USD",null,"INVOICE","fba-only",false))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.FBA_STORAGE,State.REVIEWED_ZERO,
                "USD",BigDecimal.ONE,"INVOICE","not-zero",true))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.FBA_STORAGE,State.REVIEWED_ZERO,
                "USD",BigDecimal.ZERO,null,null,true))));
        assertThrows(IllegalArgumentException.class,()->calculate(base(),List.of(
            new Evidence(Category.FBA_STORAGE,State.UNKNOWN,
                "USD",BigDecimal.ZERO,null,null,false))));
    }

    @Test void neverEmitAProvisionalTotalForUnreadyBaseOrForgedSourceMath() {
        var blocked=new OrderContributionEngine.Preview("UNIT_COST_NOT_READY","USD",
            0,0,null,null,null,null,null,false,false);
        var missing=calculate(blocked,List.of(fee(Category.ADS_SPEND,"1","ads-1")));
        assertEquals("BASE_CONTRIBUTION_NOT_READY",missing.state());
        assertNull(missing.baselineContribution());
        assertNull(missing.additionalReviewedExpense());
        assertNull(missing.knownCostAdjustedContribution());
        assertFalse(missing.realizedNetProfitVerified());
        var forged=new OrderContributionEngine.Preview(
            "REVIEWED_SOURCE_CONTRIBUTION_PREVIEW","USD",3,0,
            "20.00","-3.00","-2.00","8.00","42.00",false,false);
        assertThrows(IllegalArgumentException.class,()->calculate(forged,List.of()));
    }

    @Test void noThirdPartyClaimCanSetFinancialSourcesOrBankAsVerified() {
        var forged=new OrderContributionEngine.Preview(
            "REVIEWED_SOURCE_CONTRIBUTION_PREVIEW","USD",3,0,
            "20.00","-3.00","-2.00","8.00","7.00",true,true);
        assertThrows(IllegalArgumentException.class,()->calculate(forged,List.of()));
        var p=calculate(base(),List.of());
        assertFalse(p.fullCostSourceComplete());
        assertFalse(p.bankReconciled());
        assertFalse(p.ledgerPosted());
        assertNull(p.realizedNetProfit());
    }

    @Test void outputsAreImmutableAndDeterministicInCategoryOrder() {
        var items=new ArrayList<Evidence>();
        items.add(fee(Category.FBA_STORAGE,"2","warehouse"));
        items.add(fee(Category.ADS_SPEND,"1","ads"));
        var result=calculate(base(),items);
        items.clear();
        assertEquals(Category.ADS_SPEND,result.categories().getFirst().category());
        assertEquals(Category.OTHER_OPERATING,result.categories().getLast().category());
        assertEquals(8,result.categories().size());
        assertThrows(UnsupportedOperationException.class,()->
            result.missingCategories().add(Category.ADS_SPEND));
    }
}
