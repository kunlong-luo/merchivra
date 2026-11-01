package org.merchivra.erp.spapi;

import java.time.Duration;

/** Safe failure without leaking a remote error payload or authentication token. */
public final class SpApiHttpException extends Exception {
    private final int status;
    private final String operation;
    private final String requestId;
    private final Duration retryAfter;

    public SpApiHttpException(String operation, int status, String requestId) {
        this(operation, status, requestId, Duration.ZERO);
    }

    public SpApiHttpException(String operation, int status, String requestId,
                              Duration retryAfter) {
        super("SP-API " + operation + " failed, HTTP " + status + "; requestId=" + requestId);
        this.operation = operation;
        this.status = status;
        this.requestId = requestId;
        this.retryAfter = SpApiRetryPolicy.bounded(retryAfter);
    }

    public int status() { return status; }
    public String operation() { return operation; }
    public String requestId() { return requestId; }
    public Duration retryAfter() { return retryAfter; }
}
