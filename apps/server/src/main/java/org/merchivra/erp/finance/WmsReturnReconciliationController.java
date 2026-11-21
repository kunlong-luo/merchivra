package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** V174 signed source-to-local observation comparison: always held, NO COGS. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes/{intakeId}/wms-reconciliation")
public class WmsReturnReconciliationController {
    private final TenantAccessService access;
    private final WmsReturnReconciliationService service;
    public WmsReturnReconciliationController(TenantAccessService access,
        WmsReturnReconciliationService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getSignedWmsReturnReconciliation")
    @GetMapping
    public ResponseEntity<WmsReturnReconciliationService.Report> get(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,eventId,intakeId));
    }
    @Operation(operationId="recordSignedWmsReturnObservation")
    @PostMapping
    public ResponseEntity<WmsReturnReconciliationService.Report> record(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,
        @RequestBody WmsReturnReconciliationService.ObservationInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.record(access.currentTenant(auth,request),marketplaceId,eventId,
                intakeId,access.authenticatedUser(auth),body));
    }
}
