package org.merchivra.erp.spapi;

import java.util.Map;

/** Raw JSON remains untrusted. Never write it to logs (may contain restricted data). */
public record SpApiResponse(int statusCode, String body, Map<String,String> headers) {
    public String requestId() { return headers.getOrDefault("x-amzn-requestid", ""); }
}
