package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReconciliationEngineTest {
 private static final Instant START=Instant.parse("2026-10-01T00:00:00Z");
 private static final Instant END=Instant.parse("2026-10-31T00:00:00Z");
 private static SettlementReconciliationEngine.Event event(String id,String amount,
     String currency,Instant posted) {
   return new SettlementReconciliationEngine.Event(id,"FIN-"+id,
      "Shipment",new BigDecimal(amount),currency,posted);
 }
 @Test void signedTotalsCompareWithoutPretendingBankConfirmation() {
   var p=SettlementReconciliationEngine.compare(new BigDecimal("7.00"),"USD",START,END,
     List.of(event("a","10.00","USD",START),
       event("b","-3.00","USD",END.minusSeconds(1))),2);
   assertEquals("SIGNED_TOTAL_MATCHES_UNVERIFIED",p.status());
   assertEquals("0.00",p.signedDifference());
   assertEquals(2,p.linkedCount());
   assertEquals(2,p.unassignedSourceCount());
   assertFalse(p.bankVerified());
 }
 @Test void unknownSourceIsNotZeroPayout() {
   var p=SettlementReconciliationEngine.compare(BigDecimal.ZERO,"USD",START,END,
     List.of(),0);
   assertEquals("NO_LINKED_EVENTS",p.status());
   assertEquals(0,p.linkedCount());
   assertFalse(p.bankVerified());
 }
 @Test void signedDiscrepancyExplicitlyShown() {
   var p=SettlementReconciliationEngine.compare(new BigDecimal("7"),"USD",START,END,
     List.of(event("a","6","USD",START)),0);
   assertEquals("SIGNED_TOTAL_DIFFERS",p.status());
   assertEquals("1",p.signedDifference());
 }
 @Test void eventsOutsideDeclaredStatementPeriodAreExceptions() {
   var p=SettlementReconciliationEngine.compare(new BigDecimal("7"),"USD",START,END,
     List.of(event("a","7","USD",END)),0);
   assertEquals("OUTSIDE_STATEMENT_PERIOD",p.status());
   assertEquals(1,p.outsidePeriodCount());
 }
 @Test void mixedCurrencyCannotBeAdded() {
   var p=SettlementReconciliationEngine.compare(new BigDecimal("7"),"USD",START,END,
     List.of(event("a","7","EUR",START)),0);
   assertEquals("CURRENCY_MISMATCH",p.status());
   assertNull(p.signedDifference());
   assertNull(p.linkedSourceNet());
 }
 @Test void duplicateSourceCannotBeDoubleCounted() {
   assertThrows(IllegalArgumentException.class,()->
     SettlementReconciliationEngine.compare(BigDecimal.ONE,"USD",START,END,
       List.of(event("a","1","USD",START),event("a","1","USD",START)),0));
 }
}
