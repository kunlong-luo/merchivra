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

/** Owner Session + CSRF protected manual cost allocation; never accounting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/allocations")
public class CostAllocationController {
    private final TenantAccessService access;
    private final CostAllocationService service;
    public CostAllocationController(TenantAccessService access,CostAllocationService service) {
        this.access=access;this.service=service;
    }
    public record Decision(boolean accept,String reason) {}
    @Operation(operationId="proposeManualCostAllocation")
    @PostMapping
    public ResponseEntity<CostAllocationService.Plan> propose(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @RequestBody CostAllocationService.Draft draft) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.propose(access.currentTenant(auth,request),marketplaceId,sourceId,
                access.authenticatedUser(auth),draft));
    }
    @Operation(operationId="listManualCostAllocations")
    @GetMapping
    public ResponseEntity<List<CostAllocationService.Plan>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.list(access.currentTenant(auth,request),marketplaceId,sourceId));
    }
    @Operation(operationId="reviewManualCostAllocation")
    @PostMapping("/{allocationId}/decision")
    public ResponseEntity<CostAllocationService.Plan> decide(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @PathVariable UUID allocationId,@RequestBody Decision decision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,sourceId,
                allocationId,access.authenticatedUser(auth),
                decision.accept(),decision.reason()));
    }
    @Operation(operationId="auditManualCostAllocation")
    @GetMapping("/{allocationId}/audit")
    public ResponseEntity<List<CostAllocationService.Audit>> audit(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @PathVariable UUID allocationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,sourceId,
                allocationId));
    }
}
