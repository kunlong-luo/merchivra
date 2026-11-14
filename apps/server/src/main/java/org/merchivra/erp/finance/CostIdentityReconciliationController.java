package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** Owner-only local identity overlap snapshot; never a clearance certificate. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/identity-check")
public class CostIdentityReconciliationController {
    private final TenantAccessService access;
    private final CostIdentityReconciliationService service;
    public CostIdentityReconciliationController(TenantAccessService access,
                                                CostIdentityReconciliationService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="previewManualCostSharedSettlementLines")
    @GetMapping("/shared-settlement-lines")
    public ResponseEntity<CostIdentityReconciliationService.SharedLinePreview>
        sharedSettlementLines(@Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.sharedLinePreview(access.currentTenant(auth,request),
                marketplaceId,sourceId));
    }

    @Operation(operationId="previewManualCostIdentityOverlap")
    @GetMapping
    public ResponseEntity<CostIdentityReconciliationService.Preview> preview(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.preview(access.currentTenant(auth,request),marketplaceId,sourceId));
    }
}
