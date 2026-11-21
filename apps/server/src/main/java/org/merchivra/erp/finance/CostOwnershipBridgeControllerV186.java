package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * V186 global original-currency/manual cost ownership bridge: only existing
 * Owner Session, no mutations and no financial claims or OAuth calls.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-ownership-bridge")
public class CostOwnershipBridgeControllerV186 {
    private final TenantAccessService access;
    private final CostOwnershipBridgeServiceV186 service;

    public CostOwnershipBridgeControllerV186(TenantAccessService access,
        CostOwnershipBridgeServiceV186 service) {
        this.access=access;
        this.service=service;
    }

    @Operation(operationId="getManualCostOwnershipBridgeV186")
    @GetMapping
    public ResponseEntity<CostOwnershipBridgeServiceV186.Report> report(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam LocalDate periodStart,
        @RequestParam LocalDate periodEndExclusive) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,
                periodStart,periodEndExclusive));
    }
}
