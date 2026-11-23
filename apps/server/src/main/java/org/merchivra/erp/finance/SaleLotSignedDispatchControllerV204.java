package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V204 signed WMS key observation only: Owner, Session/CSRF, no COGS posting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class SaleLotSignedDispatchControllerV204 {
    private final TenantAccessService access;
    private final SaleLotSignedDispatchServiceV204 service;
    public SaleLotSignedDispatchControllerV204(TenantAccessService access,
        SaleLotSignedDispatchServiceV204 service) {
        this.access=access;this.service=service;
    }
    @GetMapping("/signed-dispatch")
    public ResponseEntity<SaleLotSignedDispatchServiceV204.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,request),
                marketplaceId,orderId));
    }
    @PostMapping("/sale-lot-claims/{claimId}/signed-dispatch")
    public ResponseEntity<SaleLotSignedDispatchServiceV204.SignedObservation> importSigned(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID claimId,
        @RequestBody SaleLotSignedDispatchServiceV204.SignedInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.importSigned(access.currentTenant(auth,request),
                marketplaceId,orderId,claimId,access.authenticatedUser(auth),body));
    }
}
