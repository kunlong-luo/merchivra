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

/** Owner-scoped read-only refund-to-original-local-lot quantity envelope. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class RefundBatchLineageControllerV212 {
    private final TenantAccessService access;
    private final RefundBatchLineageServiceV212 service;
    public RefundBatchLineageControllerV212(TenantAccessService access,
        RefundBatchLineageServiceV212 service) {
        this.access=access;this.service=service;
    }
    @GetMapping("/refund-batch-lineage-readiness")
    public ResponseEntity<RefundBatchLineageGuardV212.Preview> preview(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(
                access.currentTenant(authentication,request),marketplaceId,orderId));
    }
}
