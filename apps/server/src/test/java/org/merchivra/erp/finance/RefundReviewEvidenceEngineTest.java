package org.merchivra.erp.finance;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefundReviewEvidenceEngineTest {
 @Test void reviewedRefundsCannotExceedPurchasedUnitsAcrossDifferentEvents() {
  RefundReviewEvidenceEngine.requireQuantity(3,2,1);
  assertThrows(IllegalArgumentException.class,()->
    RefundReviewEvidenceEngine.requireQuantity(3,2,2));
  assertThrows(IllegalArgumentException.class,()->
    RefundReviewEvidenceEngine.requireQuantity(2,-1,0));
  assertThrows(IllegalArgumentException.class,()->
    RefundReviewEvidenceEngine.requireQuantity(0,1,0));
 }
 @Test void manualRestockStatusProvidesOnlySensitivityNotLedgerCredit() {
  assertEquals("8.250000",RefundReviewEvidenceEngine.unverifiedRestockSensitivity(
    "RESTOCKED_CLAIMED",new BigDecimal("4.125000"),2,"USD","USD"));
  assertNull(RefundReviewEvidenceEngine.unverifiedRestockSensitivity(
    "NOT_RESTOCKED",new BigDecimal("4"),2,"USD","USD"));
  assertNull(RefundReviewEvidenceEngine.unverifiedRestockSensitivity(
    "RESTOCKED_CLAIMED",new BigDecimal("4"),2,"USD","EUR"));
  assertNull(RefundReviewEvidenceEngine.unverifiedRestockSensitivity(
    "RESTOCKED_CLAIMED",null,2,"USD","USD"));
 }
}
