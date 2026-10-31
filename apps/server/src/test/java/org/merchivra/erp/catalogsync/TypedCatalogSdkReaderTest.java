package org.merchivra.erp.catalogsync;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiRegion;
import org.merchivra.erp.spapi.SpApiSellerScope;
import software.amazon.spapi.models.listings.items.v2021_08_01.Item;
import software.amazon.spapi.models.listings.items.v2021_08_01.ItemSummaries;
import software.amazon.spapi.models.listings.items.v2021_08_01.ItemSummaryByMarketplace;
import software.amazon.spapi.models.listings.items.v2021_08_01.ItemSearchResults;
import software.amazon.spapi.models.fba.inventory.v1.GetInventorySummariesResponse;
import software.amazon.spapi.models.fba.inventory.v1.GetInventorySummariesResult;
import software.amazon.spapi.models.fba.inventory.v1.InventorySummaries;
import software.amazon.spapi.models.fba.inventory.v1.InventorySummary;
import software.amazon.spapi.models.fba.inventory.v1.InventoryDetails;
import software.amazon.spapi.models.fba.inventory.v1.ReservedQuantity;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic in-memory official generated SDK model tests, no network. */
class TypedCatalogSdkReaderTest {
    private static final String MP="ATVPDKIKX0DER";
    private static SpApiSellerScope scope(boolean approved) {
        return new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),"A2SYNTH-SELLER",
            MP,SpApiRegion.NA,"local-test-token",
            approved?Set.of("Inventory and Order Tracking"):Set.of());
    }
    private static TypedCatalogSdkReader reader() {
        return new TypedCatalogSdkReader((seller,token)->{
            ItemSummaries summaries=new ItemSummaries();
            summaries.add(new ItemSummaryByMarketplace().marketplaceId(MP)
                .asin("B000TEST123").fnSku("X000SYNTH")
                .lastUpdatedDate(OffsetDateTime.parse("2026-10-30T01:00:00Z")));
            return new ItemSearchResults().items(List.of(
                new Item().sku("SKU-A").summaries(summaries)));
        },(seller,token)->{
            InventorySummaries stock=new InventorySummaries();
            stock.add(new InventorySummary().sellerSku("SKU-A")
                .lastUpdatedTime(OffsetDateTime.parse("2026-10-30T02:00:00Z"))
                .inventoryDetails(new InventoryDetails().fulfillableQuantity(8)
                    .reservedQuantity(new ReservedQuantity().totalReservedQuantity(3))));
            stock.add(new InventorySummary().sellerSku("SKU-NO-DATA"));
            return new GetInventorySummariesResponse().payload(
                new GetInventorySummariesResult().inventorySummaries(stock));
        });
    }
    @Test void typedListingsRetainMarketplaceAndSkuOnly() throws Exception {
        var data=reader().fetchListings(scope(true),null);
        assertEquals(1,data.listings().size());
        assertEquals("SKU-A",data.listings().getFirst().sellerSku());
        assertEquals("B000TEST123",data.listings().getFirst().asin());
        assertEquals("X000SYNTH",data.listings().getFirst().fnsku());
        assertNull(data.nextToken());
    }
    @Test void fbaUnknownDoesNotBecomeZeroOrObserved() throws Exception {
        var data=reader().fetchInventory(scope(true),null);
        assertEquals(2,data.stocks().size());
        var first=data.stocks().getFirst();
        assertEquals("OBSERVED",first.quality());
        assertEquals(8,first.fulfillable());
        assertEquals(3,first.reserved());
        var missing=data.stocks().get(1);
        assertEquals("PARTIAL",missing.quality());
        assertNull(missing.fulfillable());
        assertNull(missing.reserved());
        assertNull(missing.updatedAt());
    }
    @Test void roleOrCursorMissingStopsBeforeNetwork() throws Exception {
        assertThrows(SecurityException.class,()->reader().fetchListings(scope(false),null));
        assertThrows(SecurityException.class,()->reader().fetchInventory(scope(false),null));
        assertThrows(IllegalArgumentException.class,()->
            reader().fetchListings(scope(true),"x".repeat(4097)));
    }
    @Test void foreignMarketplaceListingsRejected() {
        var foreign=new TypedCatalogSdkReader((seller,token)->{
            ItemSummaries summaries=new ItemSummaries();
            summaries.add(new ItemSummaryByMarketplace().marketplaceId("A1PA6795UKMFR9"));
            return new ItemSearchResults().items(List.of(
                new Item().sku("SKU-A").summaries(summaries)));
        },(seller,token)->null);
        assertThrows(IllegalArgumentException.class,()->
            foreign.fetchListings(scope(true),null));
    }
}
