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

/** Local Owner+CSRF review, never an Amazon API proxy or accounting approval. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/settlement-line-links")
public class CostSettlementLineEvidenceController {
    private final TenantAccessService access;
    private final CostSettlementLineEvidenceService service;

    public CostSettlementLineEvidenceController(TenantAccessService access,
        CostSettlementLineEvidenceService service) {
        this.access=access;
        this.service=service;
    }
    public record Candidate(UUID reportId,Integer lineNumber) {}
    public record Review(String disposition,String reason) {}

    @Operation(operationId="attachManualCostSettlementLine")
    @PostMapping
    public ResponseEntity<CostSettlementLineEvidenceService.Link> attach(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @RequestBody Candidate candidate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.attach(access.currentTenant(auth,request),marketplaceId,
                sourceId,candidate==null?null:candidate.reportId(),
                candidate==null?null:candidate.lineNumber(),
                access.authenticatedUser(auth)));
    }
    @Operation(operationId="listManualCostSettlementLineLinks")
    @GetMapping
    public ResponseEntity<List<CostSettlementLineEvidenceService.Link>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.list(access.currentTenant(auth,request),marketplaceId,sourceId));
    }
    @Operation(operationId="reviewManualCostSettlementLineLink")
    @PostMapping("/{linkId}/review")
    public ResponseEntity<CostSettlementLineEvidenceService.Link> review(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @PathVariable UUID linkId,@RequestBody Review review) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,
                sourceId,linkId,access.authenticatedUser(auth),
                review==null?null:review.disposition(),
                review==null?null:review.reason()));
    }
    @Operation(operationId="auditManualCostSettlementLineLink")
    @GetMapping("/{linkId}/audit")
    public ResponseEntity<List<CostSettlementLineEvidenceService.Audit>> audit(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId,
        @PathVariable UUID linkId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,
                sourceId,linkId));
    }
}
