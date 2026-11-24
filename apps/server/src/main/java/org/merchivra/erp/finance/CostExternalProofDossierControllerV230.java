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

/** V230 Owner-scoped source proof checklist; never confirms real costs. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-sources/{sourceId}/external-proof-dossier")
public class CostExternalProofDossierControllerV230 {
    private final TenantAccessService access;
    private final CostExternalProofDossierServiceV230 service;
    public CostExternalProofDossierControllerV230(TenantAccessService access,
        CostExternalProofDossierServiceV230 service) {
        this.access=access;
        this.service=service;
    }
    @Operation(operationId="getManualCostExternalProofDossierV230")
    @GetMapping
    public ResponseEntity<CostExternalProofDossierServiceV230.Dossier> get(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.inspect(access.currentTenant(auth,request),
                marketplaceId,sourceId));
    }
}
