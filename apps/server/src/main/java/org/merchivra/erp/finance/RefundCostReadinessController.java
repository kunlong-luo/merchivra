package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
 * Owner-only refund COGS-reversal risk display; never actual stock/GL posting.
 * Source postedAt is UTC, independent of manual V91 business_date.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/refund-cost-readiness")
public class RefundCostReadinessController {
    private final TenantAccessService access;
    private final RefundCostReadinessService service;
    public RefundCostReadinessController(TenantAccessService access,
                                         RefundCostReadinessService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getRefundCostReadiness")
    @GetMapping
    public ResponseEntity<RefundCostReadinessService.Page> report(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam LocalDate periodStart,
        @RequestParam LocalDate periodEndExclusive,
        @RequestParam(required=false) Instant beforeAt,
        @RequestParam(required=false) UUID beforeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,
                periodStart,periodEndExclusive,beforeAt,beforeId));
    }
}
