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

/** V229 single-source read-only audit integrity; no ledger or proof promotion. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/audit-integrity")
public class CostSourceAuditIntegrityControllerV229 {
    private final TenantAccessService access;
    private final CostSourceAuditIntegrityServiceV229 service;
    public CostSourceAuditIntegrityControllerV229(TenantAccessService access,
        CostSourceAuditIntegrityServiceV229 service) {
        this.access=access;
        this.service=service;
    }
    @Operation(operationId="getManualCostAuditIntegrityV229")
    @GetMapping
    public ResponseEntity<CostSourceAuditIntegrityServiceV229.Evidence> get(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.inspect(access.currentTenant(auth,request),
                marketplaceId,sourceId));
    }
}
