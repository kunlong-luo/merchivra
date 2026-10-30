package org.merchivra.erp.spapi;

import java.util.List;
import software.amazon.spapi.models.sellers.v1.MarketplaceParticipation;

/**
 * Uses Amazon's generated Sellers API v1, not a general-purpose raw JSON proxy.
 * Returns no Seller ID or application roles: those are NOT in this API response.
 */
public final class OfficialSellerMarketplaceGateway implements SellerMarketplaceGateway {
    private final SpApiOfficialSdkFactory sdk;
    public OfficialSellerMarketplaceGateway(SpApiOfficialSdkFactory sdk) { this.sdk=sdk; }

    @Override public List<Participation> fetch(SpApiRegion region, String refreshToken) throws Exception {
        var response=sdk.sellersForVerification(region,refreshToken).getMarketplaceParticipations();
        if (response == null || response.getErrors() != null && !response.getErrors().isEmpty()
            || response.getPayload() == null)
            throw new IllegalStateException("Sellers API returned no valid participation list");
        return response.getPayload().stream()
            .map(OfficialSellerMarketplaceGateway::map)
            .toList();
    }

    private static Participation map(MarketplaceParticipation value) {
        if (value == null || value.getMarketplace() == null
            || value.getParticipation() == null)
            throw new IllegalStateException("Malformed Sellers marketplace participation");
        return new Participation(value.getMarketplace().getId(),
            Boolean.TRUE.equals(value.getParticipation().getIsParticipating()),
            !Boolean.FALSE.equals(value.getParticipation().getHasSuspendedListings()));
    }
}
