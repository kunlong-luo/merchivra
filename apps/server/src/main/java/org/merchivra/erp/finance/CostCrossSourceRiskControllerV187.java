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

/** Owner-only V187 full-period local collision evidence; no financial release. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-cross-source-risk")
public class CostCrossSourceRiskControllerV187 {
    private final TenantAccessService access;
    private final CostCrossSourceRiskServiceV187 service;
    public CostCrossSourceRiskControllerV187(TenantAccessService access,
        CostCrossSourceRiskServiceV187 service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getManualCostCrossSourceRiskV187")
    @GetMapping
    public ResponseEntity<CostCrossSourceRiskServiceV187.Report> report(
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
