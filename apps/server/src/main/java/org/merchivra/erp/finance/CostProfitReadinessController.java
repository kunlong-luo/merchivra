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

/** V169 Owner-only original-currency cost exposure, never a net profit report. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-profit-readiness")
public class CostProfitReadinessController {
    private final TenantAccessService access;
    private final CostProfitReadinessService service;
    public CostProfitReadinessController(TenantAccessService access,
                                        CostProfitReadinessService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getManualCostProfitReadiness")
    @GetMapping
    public ResponseEntity<CostProfitReadinessService.Page> report(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam LocalDate periodStart,
        @RequestParam LocalDate periodEndExclusive,
        @RequestParam(required=false) LocalDate beforeDate,
        @RequestParam(required=false) UUID beforeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,
                periodStart,periodEndExclusive,beforeDate,beforeId));
    }
}
