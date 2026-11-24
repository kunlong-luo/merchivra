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

/** Owner/session-only private audit: no mutate, ledger or credential access. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/purchase-receipts/{receiptId}")
public class ReceiptCostIntegrityControllerV216 {
    private final TenantAccessService access;
    private final ReceiptCostIntegrityServiceV216 service;
    public ReceiptCostIntegrityControllerV216(TenantAccessService access,
        ReceiptCostIntegrityServiceV216 service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getPurchaseReceiptManualCostIntegrityV216")
    @GetMapping("/manual-cost-integrity")
    public ResponseEntity<ReceiptCostIntegrityGateV216.Result> preview(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID receiptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(authentication,request),
                marketplaceId,receiptId));
    }
}
