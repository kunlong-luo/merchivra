package org.merchivra.erp.spapi;

import java.util.Set;
import java.util.UUID;

/**
 * Construct ONLY after server-side tenant/account/Marketplace authorization.
 * The refresh token comes from a secure per-Seller vault, not browser input.
 * A sandbox placeholder sellerId such as LOCAL-* is never a valid live scope.
 */
public record SpApiSellerScope(UUID tenantId, UUID sellerAccountId,
        String sellerId, String marketplaceId, SpApiRegion region,
        String refreshToken, Set<String> approvedRoles,
        Long grantRevision, Long financeRoleRevision) {
    public SpApiSellerScope(UUID tenantId,UUID sellerAccountId,String sellerId,
        String marketplaceId,SpApiRegion region,String refreshToken,Set<String> approvedRoles) {
        this(tenantId,sellerAccountId,sellerId,marketplaceId,region,refreshToken,
            approvedRoles,null,null);
    }
    public boolean hasReportsRevisions() {
        return grantRevision!=null && grantRevision>0
            && financeRoleRevision!=null && financeRoleRevision>0
            && approvedRoles.contains("Finance and Accounting");
    }
    @Override public String toString() {
        return "SpApiSellerScope[tenantId=" + tenantId + ",sellerAccountId=" + sellerAccountId
            + ",sellerId=<redacted>,marketplaceId=" + marketplaceId
            + ",region=" + region + ",refreshToken=<redacted>,approvedRoles=<redacted>]";
    }

    public SpApiSellerScope {
        if (tenantId == null || sellerAccountId == null || region == null
            || sellerId == null || sellerId.isBlank()
            || sellerId.startsWith("LOCAL-") || sellerId.startsWith("MOCK-")
            || marketplaceId == null || marketplaceId.isBlank()
            || refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Approved Seller scope required; Mock/LOCAL accounts prohibited");
        }
        approvedRoles = Set.copyOf(approvedRoles);
    }
}
