package org.merchivra.erp.spapi;

import java.time.Duration;

/** Safe transient Redis admission denial; credentials and Redis keys are redacted. */
public final class SpApiAdmissionException extends RuntimeException {
    public enum Reason { COOLDOWN, STORE_UNAVAILABLE }
    private final Reason reason;
    private final Duration retryAfter;

    public SpApiAdmissionException(Reason reason) {
        this(reason, Duration.ZERO);
    }

    public SpApiAdmissionException(Reason reason, Duration retryAfter) {
        super(reason == Reason.COOLDOWN
            ? "SP-API Redis cooldown active; outbound deferred"
            : "Redis SP-API admission unavailable; outbound denied");
        this.reason = reason;
        this.retryAfter = SpApiRetryPolicy.bounded(retryAfter);
    }

    public Reason reason() { return reason; }
    public Duration retryAfter() { return retryAfter; }
}
