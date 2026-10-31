package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderContributionEngineTest {
 private static OrderCostEngine.Preview cost() {
  return OrderCostEngine.evaluate(true,true,Instant.parse("2026-10-10T00:00:00Z"),
    "USD",List.of(new OrderCostEngine.Item("i","SKU",2,"LIVE","P",
      new BigDecimal("4.00"),"USD")));
 }
 private static OrderContributionEngine.CategoryAmount cat(String type,String amount) {
  return new OrderContributionEngine.CategoryAmount(type,"USD",new BigDecimal(amount));
 }
 private static OrderContributionEngine.Transaction tx(String id,String type,String amount) {
  return new OrderContributionEngine.Transaction(id,"RECONCILED_UNVERIFIED",
    List.of(cat(type,amount)));
 }
 @Test void signedFeesDoNotGetSubtractedTwice() {
  var p=OrderContributionEngine.calculate("USD",cost(),List.of(
    tx("sale","PRODUCT_SALES","20.00"),
    tx("commission","AMAZON_FEE","-3.00"),
    tx("fba","FBA_FEE","-2.00")),0,false);
  assertEquals("REVIEWED_SOURCE_CONTRIBUTION_PREVIEW",p.readiness());
  assertEquals("7.00",p.estimatedContribution());
  assertEquals("-3.00",p.signedAmazonFees());
  assertEquals("8.00",p.provisionalUnitCost());
  assertFalse(p.financialSourceComplete());
  assertFalse(p.bankSettlementVerified());
 }
 @Test void refundsRequireInventoryCostReversalEvidence() {
  var p=OrderContributionEngine.calculate("USD",cost(),List.of(
    tx("sale","PRODUCT_SALES","20"),
    tx("refund","PRODUCT_REFUND","-5")),0,false);
  assertEquals("REFUND_COST_REVERSAL_UNVERIFIED",p.readiness());
  assertNull(p.estimatedContribution());
 }
 @Test void noReviewedRevenueDoesNotPretendToBeProfit() {
  var p=OrderContributionEngine.calculate("USD",cost(),
    List.of(tx("commission","AMAZON_FEE","-2")),0,false);
  assertEquals("PRODUCT_SALES_NOT_REVIEWED",p.readiness());
 }
 @Test void unreviewedOrderLinkBlocksAmounts() {
  var p=OrderContributionEngine.calculate("USD",cost(),
    List.of(tx("sale","PRODUCT_SALES","20")),1,false);
  assertEquals("ORDER_LINKS_AWAITING_REVIEW",p.readiness());
  assertNull(p.signedSales());
 }
 @Test void classifiedButUnreconciledSourceBlocksAmounts() {
  var p=OrderContributionEngine.calculate("USD",cost(),List.of(
    new OrderContributionEngine.Transaction("a","AMOUNT_MISMATCH",
      List.of(cat("PRODUCT_SALES","20")))),0,false);
  assertEquals("FINANCES_CLASSIFICATION_INCOMPLETE",p.readiness());
 }
 @Test void olderOrUnmappedCostBlocksAmounts() {
  var missing=OrderCostEngine.evaluate(true,true,null,"USD",List.of());
  var p=OrderContributionEngine.calculate("USD",missing,
    List.of(tx("sale","PRODUCT_SALES","20")),0,false);
  assertEquals("UNIT_COST_NOT_READY",p.readiness());
 }
 @Test void scanTruncationBlocksAmounts() {
  var p=OrderContributionEngine.calculate("USD",cost(),
    List.of(tx("sale","PRODUCT_SALES","20")),0,true);
  assertEquals("SOURCE_RESULT_TRUNCATED",p.readiness());
 }
 @Test void duplicateFinancialEventCannotIncreaseContribution() {
  assertThrows(IllegalArgumentException.class,()->
    OrderContributionEngine.calculate("USD",cost(),List.of(
      tx("same","PRODUCT_SALES","20"),tx("same","PRODUCT_SALES","20")),0,false));
 }
 @Test void adjustmentAndCrossCurrencyAreNotAutomated() {
  assertEquals("ADJUSTMENT_ATTRIBUTION_UNVERIFIED",
    OrderContributionEngine.calculate("USD",cost(),List.of(
      tx("sale","PRODUCT_SALES","20"),
      tx("adjust","ADJUSTMENT","-1")),0,false).readiness());
  var wrong=new OrderContributionEngine.Transaction("sale","RECONCILED_UNVERIFIED",
    List.of(new OrderContributionEngine.CategoryAmount("PRODUCT_SALES","EUR",
      new BigDecimal("20"))));
  assertEquals("CURRENCY_MISMATCH",OrderContributionEngine.calculate("USD",
    cost(),List.of(wrong),0,false).readiness());
 }
}
