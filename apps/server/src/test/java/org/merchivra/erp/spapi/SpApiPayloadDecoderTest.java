package org.merchivra.erp.spapi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpApiPayloadDecoderTest {
    private final SpApiPayloadDecoder decoder = new SpApiPayloadDecoder();

    @Test
    void orders2026ReadsOfficialPaginationShapeAndKeepsMissingStatesUnknown() {
        var page = decoder.orders2026("""
            {"orders":[{"orderId":"TEST-001","createdTime":"2026-10-01T00:00:00Z",
             "lastUpdatedTime":"2026-10-02T00:00:00Z",
             "salesChannel":{"marketplaceId":"ATVPDKIKX0DER"}}],
             "pagination":{"nextToken":"PAGE-TWO"}}""");
        assertEquals(1, page.orders().size());
        assertEquals("TEST-001", page.orders().getFirst().orderId());
        assertEquals("ATVPDKIKX0DER", page.orders().getFirst().marketplaceId());
        assertEquals("NOT_OBSERVED", page.orders().getFirst().fulfillmentRaw());
        assertEquals("PAGE-TWO", page.nextToken());
        assertNull(decoder.orders2026("{\"orders\":[],\"pagination\":{}}").nextToken());
        assertThrows(IllegalArgumentException.class, () ->
            decoder.orders2026("{\"orders\":[{\"lastUpdatedTime\":\"2026-10-01T00:00:00Z\"}]}"));
    }

    @Test
    void fbaDetailsRespectUnknownVersusZero() {
        var inventory = decoder.fbaInventoryV1("""
            {"payload":{"inventorySummaries":[
              {"sellerSku":"UNKNOWN","inventoryDetails":{}},
              {"sellerSku":"OBSERVED-ZERO","inventoryDetails":{
                "fulfillableQuantity":0,"reservedQuantity":{"totalReservedQuantity":0}}},
              {"sellerSku":"PARTIAL","inventoryDetails":{"fulfillableQuantity":12}}
            ]}}""");
        assertEquals("UNKNOWN", inventory.observations().get(0).quality());
        assertNull(inventory.observations().get(0).fulfillable());
        assertEquals("OBSERVED", inventory.observations().get(1).quality());
        assertEquals(0, inventory.observations().get(1).fulfillable());
        assertEquals("PARTIAL", inventory.observations().get(2).quality());
        assertNull(inventory.observations().get(2).reserved());
        assertThrows(IllegalArgumentException.class, () ->
            decoder.fbaInventoryV1("{\"payload\":{\"inventorySummaries\":[{\"sellerSku\":\"S\",\"inventoryDetails\":{\"fulfillableQuantity\":-1}}]}}"));
    }
}
