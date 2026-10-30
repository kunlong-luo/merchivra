package org.merchivra.erp.spapi;

import java.util.List;

/** Narrow Sellers v1 interface. No arbitrary endpoint or caller-provided token. */
@FunctionalInterface
public interface SellerMarketplaceGateway {
    record Participation(String marketplaceId, boolean participating, boolean suspended) {}
    List<Participation> fetch(SpApiRegion region, String refreshToken) throws Exception;
}
