package org.merchivra.erp.spapi;

import java.util.Map;

/** Raw JSON remains untrusted. Never write it to logs (may contain restricted data). */
public record SpApiResponse(int statusCode, String body, Map<String,String> headers) {
    @Override public String toString() {
        return "SpApiResponse[statusCode=" + statusCode + ",body=<redacted>,headers=<redacted>]";
    }
    public String requestId() { return headers.getOrDefault("x-amzn-requestid", ""); }
}
