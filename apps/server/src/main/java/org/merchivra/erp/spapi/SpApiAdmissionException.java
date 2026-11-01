package org.merchivra.erp.spapi;

/** Safe transient local Redis admission denial, never includes any credentials. */
public final class SpApiAdmissionException extends RuntimeException {
    public enum Reason { COOLDOWN, STORE_UNAVAILABLE }
    private final Reason reason;

    public SpApiAdmissionException(Reason reason) {
        super(reason == Reason.COOLDOWN
            ? "SP-API Redis cooldown active; outbound deferred"
            : "Redis SP-API admission unavailable; outbound denied");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
