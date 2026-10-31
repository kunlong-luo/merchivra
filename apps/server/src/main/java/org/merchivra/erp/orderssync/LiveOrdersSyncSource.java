package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.Objects;

/**
 * LIVE source backed by V12 audited, role-checked Orders read gateway.
 *
 * No automatic bean outside the explicit LIVE mode + multi-flag opt-in.
 * The persistent worker owns seller/tenant validation and atomic commits;
 * this source NEVER logs tokens or raw buyer/recipient payloads.
 */
public final class LiveOrdersSyncSource implements OrdersSyncSource {
    private final ApprovedOrdersReadGateway gateway;

    public LiveOrdersSyncSource(ApprovedOrdersReadGateway gateway) {
        this.gateway=Objects.requireNonNull(gateway);
    }

    @Override public String dataOrigin() { return "LIVE"; }
    @Override public boolean allowed() { return true; }

    @Override public void requireAuthorized(Scope scope) {
        if(scope==null || scope.tenant()==null || scope.marketplace()==null
           || scope.marketplaceCode()==null || scope.marketplaceCode().isBlank())
            throw new SecurityException("Trusted Seller scope required");
        gateway.requireAuthorized(scope.tenant(),scope.marketplace(),scope.marketplaceCode());
    }

    @Override public Page fetch(Scope scope, Instant after, Instant before, String cursor)
        throws Exception {
        requireAuthorized(scope);
        return gateway.search(scope.tenant(),scope.marketplace(),after,before,cursor);
    }
}
