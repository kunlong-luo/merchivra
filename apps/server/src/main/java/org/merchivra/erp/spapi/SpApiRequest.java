package org.merchivra.erp.spapi;

import java.util.Map;
import java.util.Set;

/**
 * Scope is trusted application-side input only; never deserialize it from
 * a public HTTP request. Grant verification is a later, explicit integration.
 */
public record SpApiRequest(
    SpApiRegion region,
    SpApiOperation operation,
    Map<String, String> pathVariables,
    Map<String, String> query,
    String jsonBody,
    Set<String> grantedRoles
) {
    @Override public String toString() {
        return "SpApiRequest[region=" + region + ",operation=" + operation
            + ",pathVariables=<redacted>,query=<redacted>,jsonBody=<redacted>]";
    }

    public SpApiRequest {
        if (region == null || operation == null) throw new IllegalArgumentException("Region and operation required");
        pathVariables = Map.copyOf(pathVariables);
        query = Map.copyOf(query);
        grantedRoles = Set.copyOf(grantedRoles);
        operation.checkQuery(query);
        if (operation.requiresFinanceRole() && !grantedRoles.contains("Finance and Accounting")) {
            throw new SecurityException("Finances role not verified; do not request transactions");
        }
        if (operation.mutatesRemoteState() && (jsonBody == null || jsonBody.isBlank())) {
            throw new IllegalArgumentException("Write operation requires reviewed JSON payload");
        }
        if (!operation.mutatesRemoteState() && jsonBody != null) {
            throw new IllegalArgumentException("GET must not contain a body");
        }
    }
    public static SpApiRequest read(SpApiRegion region, SpApiOperation operation,
                                    Map<String,String> path, Map<String,String> query,
                                    Set<String> roles) {
        return new SpApiRequest(region, operation, path, query, null, roles);
    }
}
