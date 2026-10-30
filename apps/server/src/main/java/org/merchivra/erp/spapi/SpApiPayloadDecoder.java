package org.merchivra.erp.spapi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conservative, version-specific parsing. Missing financial/stock components
 * are UNKNOWN, never zero. This parser never persists sensitive source JSON.
 * Orders v2026 is intentionally limited to safe order summary fields.
 */
public final class SpApiPayloadDecoder {
    private final JsonMapper mapper = JsonMapper.builder().build();

    public record OrderSummary(String orderId, String marketplaceId,
                               Instant lastUpdated, String fulfillmentRaw) {}
    public record OrderPage(List<OrderSummary> orders, String nextToken) {}
    public record FbaStock(String sellerSku, Integer fulfillable,
                           Integer reserved, String quality) {}
    public record InventoryPage(List<FbaStock> observations, String nextToken) {}

    public OrderPage orders2026(String json) {
        JsonNode root = mapper.readTree(json);
        JsonNode rows = root.path("orders");
        if (!rows.isArray()) throw new IllegalArgumentException("Orders v2026: missing orders array");
        List<OrderSummary> results = new ArrayList<>();
        for (JsonNode item : rows) {
            String id = string(item, "orderId");
            String update = string(item, "lastUpdatedTime");
            if (id == null || update == null)
                throw new IllegalArgumentException("Orders response missing stable identity/time");
            String marketplace = string(item.path("salesChannel"), "marketplaceId");
            String fulfillment = string(item.path("fulfillment"), "status");
            results.add(new OrderSummary(id, marketplace, Instant.parse(update),
                fulfillment == null ? "NOT_OBSERVED" : fulfillment));
        }
        return new OrderPage(List.copyOf(results), string(root.path("pagination"), "nextToken"));
    }

    public InventoryPage fbaInventoryV1(String json) {
        JsonNode root = mapper.readTree(json), payload = root.path("payload");
        JsonNode rows = payload.path("inventorySummaries");
        if (!rows.isArray()) throw new IllegalArgumentException("FBA: missing inventorySummaries array");
        List<FbaStock> results = new ArrayList<>();
        for (JsonNode item : rows) {
            String sku = string(item, "sellerSku");
            if (sku == null) throw new IllegalArgumentException("Inventory missing sellerSku");
            JsonNode details = item.path("inventoryDetails");
            Integer fulfillable = integer(details, "fulfillableQuantity");
            Integer reserved = integer(details.path("reservedQuantity"), "totalReservedQuantity");
            if ((fulfillable != null && fulfillable < 0) || (reserved != null && reserved < 0))
                throw new IllegalArgumentException("Negative FBA quantity");
            String quality = fulfillable == null && reserved == null ? "UNKNOWN"
                : fulfillable != null && reserved != null ? "OBSERVED" : "PARTIAL";
            results.add(new FbaStock(sku, fulfillable, reserved, quality));
        }
        return new InventoryPage(List.copyOf(results), string(payload.path("pagination"), "nextToken"));
    }

    private static String string(JsonNode node, String property) {
        JsonNode value = node.path(property);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
            ? null : value.asText();
    }
    private static Integer integer(JsonNode node, String property) {
        JsonNode value = node.path(property);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("Invalid inventory quantity");
        return value.asInt();
    }
}
