package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V209 read-only, no-store Owner route for offline certificate-chain/CRL
 * technical readiness. No certificate upload, credential, signer authority,
 * issuer-identity approval, Amazon interaction or COGS posting route.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class CustodyExternalTrustControllerV209 {
    private final TenantAccessService access;
    private final CustodyExternalTrustReadinessV209 service;

    public CustodyExternalTrustControllerV209(TenantAccessService access,
        CustodyExternalTrustReadinessV209 service) {
        this.access=access;this.service=service;
    }

    @GetMapping("/custody-external-trust-readiness")
    public ResponseEntity<CustodyExternalTrustReadinessV209.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,request),
                marketplaceId,orderId));
    }
}
