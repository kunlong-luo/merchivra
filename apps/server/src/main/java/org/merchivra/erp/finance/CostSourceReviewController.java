package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * Owner Session + existing CSRF guard. No tenant supplied by browser, no
 * finance ledger posting, no AWS API or independent invoice verification.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources")
public class CostSourceReviewController {
    private final TenantAccessService access;
    private final CostSourceReviewService service;
    public CostSourceReviewController(TenantAccessService access,
                                      CostSourceReviewService service) {
        this.access=access;this.service=service;
    }
    public record Decision(boolean accept,String reason) {}
    @Operation(operationId="createManualCostSource")
    @PostMapping
    public ResponseEntity<CostSourceReviewService.Source> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@RequestBody CostSourceReviewService.Draft draft) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.create(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),draft));
    }
    @Operation(operationId="listManualCostSources")
    @GetMapping
    public ResponseEntity<List<CostSourceReviewService.Source>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.list(access.currentTenant(auth,request),marketplaceId));
    }
    @Operation(operationId="reviewManualCostSource")
    @PostMapping("/{sourceId}/review")
    public ResponseEntity<CostSourceReviewService.Source> review(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @RequestBody Decision decision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,sourceId,
                access.authenticatedUser(auth),decision.accept(),decision.reason()));
    }
    @Operation(operationId="auditManualCostSource")
    @GetMapping("/{sourceId}/audit")
    public ResponseEntity<List<CostSourceReviewService.Audit>> audit(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,sourceId));
    }
}
