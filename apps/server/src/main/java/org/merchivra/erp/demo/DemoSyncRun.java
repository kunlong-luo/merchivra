package org.merchivra.erp.demo;

/** Demonstration-only sync status, not a live SP-API synchronization run. */
public record DemoSyncRun(
    String id,
    String status,
    String source,
    int fixtureCount,
    String failureCode,
    String startedAt,
    String finishedAt
) {
    public boolean retryable() { return "FAILED_RETRYABLE".equals(status); }
}
