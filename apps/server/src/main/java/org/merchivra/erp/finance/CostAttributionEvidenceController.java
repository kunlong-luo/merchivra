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
import io.swagger.v3.oas.annotations.Parameter;

/**
 * V182 read-only, tenant-session-scoped V94/V95/V102 evidence assessment.
 * V94 review endpoints remain the only existing independent decisions.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/settlement-attribution")
public class CostAttributionEvidenceController {
    private final TenantAccessService access;
    private final CostAttributionEvidenceService service;

    public CostAttributionEvidenceController(TenantAccessService access,
        CostAttributionEvidenceService service) {
        this.access=access;
        this.service=service;
    }

    @Operation(operationId="getManualCostSettlementAttributionEvidence")
    @GetMapping
    public ResponseEntity<CostAttributionEvidenceService.Assessment> get(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.assess(access.currentTenant(auth,request),marketplaceId,sourceId));
    }
}
