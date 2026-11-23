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

/** Owner, Session, tenant+Marketplace scoped; no local/external cost posting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class RefundGlobalReceiptCapacityControllerV213 {
    private final TenantAccessService access;
    private final RefundGlobalReceiptCapacityServiceV213 service;

    public RefundGlobalReceiptCapacityControllerV213(TenantAccessService access,
        RefundGlobalReceiptCapacityServiceV213 service) {
        this.access=access;this.service=service;
    }

    @GetMapping("/refund-original-receipt-capacity")
    public ResponseEntity<RefundGlobalReceiptCapacityGateV213.Result> preview(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(authentication,request),
                marketplaceId,orderId));
    }
}
