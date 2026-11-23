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
import io.swagger.v3.oas.annotations.Operation;

/** Owner-only read of unverified metadata; no V91/V92 writes or COGS posting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class RefundReceiptCostProvenanceControllerV214 {
    private final TenantAccessService access;
    private final RefundReceiptCostProvenanceServiceV214 service;

    public RefundReceiptCostProvenanceControllerV214(TenantAccessService access,
        RefundReceiptCostProvenanceServiceV214 service) {
        this.access = access;
        this.service = service;
    }

    @Operation(operationId = "getRefundOriginalReceiptManualCostProvenanceV214")
    @GetMapping("/refund-original-receipt-cost-provenance")
    public ResponseEntity<RefundReceiptCostProvenanceGateV214.Result> preview(
        Authentication authentication, HttpServletRequest request,
        @PathVariable UUID marketplaceId, @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(authentication, request),
                marketplaceId, orderId));
    }
}
