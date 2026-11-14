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

/** Session Owner + inherited CSRF. Triage cannot turn a collision into profit. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/collision-cases")
public class CostIdentityCaseController {
    private final TenantAccessService access;
    private final CostIdentityCaseService service;
    public CostIdentityCaseController(TenantAccessService access,CostIdentityCaseService service) {
        this.access=access;this.service=service;
    }
    public record Decision(String disposition,String reason) {}

    @Operation(operationId="scanManualCostIdentityCases")
    @PostMapping("/scan")
    public ResponseEntity<List<CostIdentityCaseService.CaseView>> scan(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.scan(access.currentTenant(auth,request),marketplaceId,
                sourceId,access.authenticatedUser(auth)));
    }
    @Operation(operationId="listManualCostIdentityCases")
    @GetMapping
    public ResponseEntity<List<CostIdentityCaseService.CaseView>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.list(access.currentTenant(auth,request),marketplaceId,sourceId));
    }
    @Operation(operationId="triageManualCostIdentityCase")
    @PostMapping("/{caseId}/review")
    public ResponseEntity<CostIdentityCaseService.CaseView> review(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @PathVariable UUID caseId,@RequestBody Decision decision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,
                sourceId,caseId,access.authenticatedUser(auth),
                decision.disposition(),decision.reason()));
    }
    @Operation(operationId="auditManualCostIdentityCase")
    @GetMapping("/{caseId}/audit")
    public ResponseEntity<List<CostIdentityCaseService.Audit>> audit(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @PathVariable UUID caseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,
                sourceId,caseId));
    }
}
