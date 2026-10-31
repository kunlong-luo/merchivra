package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.merchivra.erp.spapi.SpApiRegion;
import software.amazon.spapi.models.orders.v2026_01_01.Order;
import software.amazon.spapi.models.orders.v2026_01_01.SalesChannel;
import software.amazon.spapi.models.orders.v2026_01_01.OrderFulfillment;
import software.amazon.spapi.models.orders.v2026_01_01.FulfillmentStatus;
import software.amazon.spapi.models.orders.v2026_01_01.Pagination;
import software.amazon.spapi.models.orders.v2026_01_01.SearchOrdersResponse;
import static org.junit.jupiter.api.Assertions.*;

/** Offline synthetic typed SDK fixtures; never opens a remote connection. */
class TypedOrdersSdkReaderTest {
    private static final String MP="ATVPDKIKX0DER";
    private static final Instant AFTER=Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant BEFORE=Instant.parse("2026-10-30T12:00:00Z");

    private SpApiSellerScope grant(Set<String> roles) {
        return new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),
            "A2TESTSELLER1",MP,SpApiRegion.NA,"synthetic-fixture-token",roles);
    }
    private SearchOrdersResponse fixture(String marketplace,String next) {
        return new SearchOrdersResponse().orders(List.of(
            new Order().orderId("SYNTH-ORDER-1")
                .lastUpdatedTime(OffsetDateTime.ofInstant(AFTER.plusSeconds(3600),ZoneOffset.UTC))
                .salesChannel(new SalesChannel().marketplaceId(marketplace))
                .fulfillment(new OrderFulfillment().fulfillmentStatus(FulfillmentStatus.SHIPPED))))
            .pagination(next==null?null:new Pagination().nextToken(next));
    }

    @Test void mapsOfficialModelWithoutBuyerAddressOrFinancialFields() throws Exception {
        var calls=new AtomicInteger();
        var reader=new TypedOrdersSdkReader((scope,after,before,cursor)-> {
            calls.incrementAndGet();
            assertEquals(MP,scope.marketplaceId());
            assertEquals(AFTER,after.toInstant());
            assertEquals(BEFORE,before.toInstant());
            assertEquals("OPAQUE-2",cursor);
            return fixture(MP,"OPAQUE-3");
        });
        var page=reader.search(grant(Set.of("Inventory and Order Tracking")),
            AFTER,BEFORE,"OPAQUE-2");
        assertEquals(1,calls.get());
        assertEquals("SYNTH-ORDER-1",page.orders().getFirst().orderId());
        assertEquals(MP,page.orders().getFirst().marketplaceId());
        assertEquals("SHIPPED",page.orders().getFirst().fulfillmentRaw());
        assertEquals(AFTER.plusSeconds(3600),page.orders().getFirst().lastUpdated());
        assertEquals("OPAQUE-3",page.nextToken());
    }

    @Test void missingRoleFailsBeforeInvokingSdk() throws Exception {
        var calls=new AtomicInteger();
        var reader=new TypedOrdersSdkReader((scope,a,b,c)->{
            calls.incrementAndGet();return fixture(MP,null);
        });
        assertThrows(SecurityException.class,()->reader.search(grant(Set.of()),AFTER,BEFORE,null));
        assertEquals(0,calls.get());
    }

    @Test void rejectsCrossMarketplaceAndInvalidPages() {
        var mismatched=new TypedOrdersSdkReader((scope,a,b,c)->
            fixture("A1PA6795UKMFR9",null));
        var approved=grant(Set.of("Inventory and Order Tracking"));
        assertThrows(IllegalArgumentException.class,
            ()->mismatched.search(approved,AFTER,BEFORE,null));
        var invalid=new TypedOrdersSdkReader((scope,a,b,c)->
            new SearchOrdersResponse().orders(null));
        assertThrows(IllegalArgumentException.class,
            ()->invalid.search(approved,AFTER,BEFORE,null));
        assertThrows(IllegalArgumentException.class,
            ()->mismatched.search(approved,BEFORE,AFTER,null));
        assertThrows(IllegalArgumentException.class,
            ()->mismatched.search(approved,AFTER,BEFORE,"x".repeat(4097)));
    }

    @Test void missingFulfillmentIsUnverifiedNotShippingComplete() throws Exception {
        var reader=new TypedOrdersSdkReader((scope,a,b,c) ->
            new SearchOrdersResponse().orders(List.of(
                new Order().orderId("SYNTH-UNKNOWN")
                    .lastUpdatedTime(OffsetDateTime.ofInstant(AFTER,ZoneOffset.UTC))
                    .salesChannel(new SalesChannel().marketplaceId(MP)))));
        var result=reader.search(grant(Set.of("Inventory and Order Tracking")),
            AFTER,BEFORE,null);
        assertEquals("NOT_OBSERVED",result.orders().getFirst().fulfillmentRaw());
    }
}
