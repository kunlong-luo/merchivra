package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiRegion;
import org.merchivra.erp.spapi.SpApiSellerScope;
import software.amazon.spapi.models.finances.v2024_06_19.*;
import static org.junit.jupiter.api.Assertions.*;

/** Official generated in-memory models, no Seller credentials or network. */
class TypedFinancesSdkReaderTest {
 private static final String SITE="ATVPDKIKX0DER";
 private static final Instant AFTER=Instant.parse("2026-10-10T00:00:00Z");
 private static final Instant BEFORE=Instant.parse("2026-10-20T00:00:00Z");
 private SpApiSellerScope grant(boolean role){
  return new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),
    "SYNTH-SELLER-1",SITE,SpApiRegion.NA,"fixture-not-real-token",
    role?Set.of("Finance and Accounting"):Set.of());
 }
 private ListTransactionsResponse page(String marketplace){
  Transactions tx=new Transactions();
  tx.add(new Transaction().transactionId("FIN-SYNTH-001")
    .transactionType("Shipment").postedDate(OffsetDateTime.parse("2026-10-12T01:00:00Z"))
    .totalAmount(new Currency().currencyAmount(new BigDecimal("-4.250000")).currencyCode("USD"))
    .sellingPartnerMetadata(new SellingPartnerMetadata()
      .sellingPartnerId("SYNTH-SELLER-1").marketplaceId(marketplace)));
  return new ListTransactionsResponse().payload(new TransactionsPayload().transactions(tx));
 }
 @Test void storesNetSignedTotalAsUnclassifiedNotRevenue()throws Exception{
  var reader=new TypedFinancesSdkReader((scope,after,before,cursor)->page(SITE));
  var result=reader.fetch(grant(true),AFTER,BEFORE,null);
  assertEquals(1,result.events().size());
  var fact=result.events().getFirst();
  assertEquals("UNCLASSIFIED",fact.category());
  assertEquals("-4.250000",fact.amount());
  assertEquals("USD",fact.currency());
  assertEquals("Shipment",fact.sourceType());
  assertTrue(fact.eventKey().startsWith("FIN-"));
  assertNull(fact.externalOrderId());
 }
 @Test void duplicateSourceKeyIsStableAcrossRepeatedPages()throws Exception{
  var reader=new TypedFinancesSdkReader((scope,a,b,c)->page(SITE));
  String x=reader.fetch(grant(true),AFTER,BEFORE,null).events().getFirst().eventKey();
  String y=reader.fetch(grant(true),AFTER,BEFORE,null).events().getFirst().eventKey();
  assertEquals(x,y);assertEquals(68,x.length());
 }
 @Test void missingRoleAndInvalidWindowNeverCallAmazon(){
  var hits=new AtomicInteger();
  var reader=new TypedFinancesSdkReader((scope,a,b,c)->{
   hits.incrementAndGet();return page(SITE);
  });
  assertThrows(SecurityException.class,()->reader.fetch(grant(false),AFTER,BEFORE,null));
  assertThrows(IllegalArgumentException.class,()->reader.fetch(grant(true),BEFORE,AFTER,null));
  assertThrows(IllegalArgumentException.class,()->
    reader.fetch(grant(true),AFTER,BEFORE,"x".repeat(4097)));
  assertEquals(0,hits.get());
 }
 @Test void foreignMarketplaceRejectedBeforePersist(){
  var reader=new TypedFinancesSdkReader((scope,a,b,c)->page("A1PA6795UKMFR9"));
  assertThrows(IllegalArgumentException.class,()->
    reader.fetch(grant(true),AFTER,BEFORE,null));
 }
 @Test void missingCurrencyNeverInventsZero(){
  var reader=new TypedFinancesSdkReader((scope,a,b,c)->{
   Transactions rows=new Transactions();
   rows.add(new Transaction().transactionId("FIN-SYNTH-002")
     .postedDate(OffsetDateTime.parse("2026-10-12T01:00:00Z")));
   return new ListTransactionsResponse().payload(new TransactionsPayload().transactions(rows));
  });
  assertThrows(IllegalArgumentException.class,()->
    reader.fetch(grant(true),AFTER,BEFORE,null));
 }
}
