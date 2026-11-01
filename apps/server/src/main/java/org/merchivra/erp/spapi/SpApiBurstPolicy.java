package org.merchivra.erp.spapi;

import java.util.EnumMap;
import java.util.Map;

/**
 * Explicit operator-approved burst capacity, never inferred from Amazon's
 * best-effort rate header. Default is one request: no assumed burst.
 * Syntax: SEARCH_ORDERS=2,GET_FBA_INVENTORY=3.
 */
public final class SpApiBurstPolicy {
    private final Map<SpApiOperation,Integer> overrides;

    public SpApiBurstPolicy(String configuration) {
        var parsed = new EnumMap<SpApiOperation,Integer>(SpApiOperation.class);
        if (configuration != null && !configuration.isBlank()) {
            for (String segment : configuration.split(",", -1)) {
                String[] parts = segment.trim().split("=", -1);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
                    throw new IllegalArgumentException("Invalid SP-API approved burst mapping");
                try {
                    SpApiOperation operation = SpApiOperation.valueOf(parts[0].trim());
                    int capacity = Integer.parseInt(parts[1].trim());
                    if (operation.mutatesRemoteState() || capacity < 2 || capacity > 10
                        || parsed.putIfAbsent(operation, capacity) != null)
                        throw new IllegalArgumentException("Unsafe or duplicate SP-API burst mapping");
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("Invalid SP-API approved burst mapping", error);
                }
            }
        }
        overrides = Map.copyOf(parsed);
    }

    public int capacity(SpApiOperation operation) {
        return overrides.getOrDefault(operation, 1);
    }
}
