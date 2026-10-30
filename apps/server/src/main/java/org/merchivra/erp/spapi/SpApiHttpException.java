package org.merchivra.erp.spapi;

/** Safe failure without leaking a remote error payload or authentication token. */
public final class SpApiHttpException extends Exception {
    private final int status;
    private final String operation;
    private final String requestId;
    public SpApiHttpException(String operation, int status, String requestId) {
        super("SP-API " + operation + " failed, HTTP " + status + "; requestId=" + requestId);
        this.operation=operation; this.status=status; this.requestId=requestId;
    }
    public int status() { return status; }
    public String operation() { return operation; }
    public String requestId() { return requestId; }
}
