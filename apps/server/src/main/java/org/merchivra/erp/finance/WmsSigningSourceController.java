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

/** V173 tenant-owned source keys: cryptographic validation, not WMS attestation. */
@RestController
@RequestMapping("/api/v1/finance/wms-signing-sources")
public class WmsSigningSourceController {
    private final TenantAccessService access;
    private final WmsSignedReturnService service;
    public WmsSigningSourceController(TenantAccessService access,WmsSignedReturnService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="listWmsReturnSigningSources")
    @GetMapping
    public ResponseEntity<List<WmsSignedReturnService.Source>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @RequestParam UUID warehouseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.sources(access.currentTenant(auth,request),warehouseId));
    }
    @Operation(operationId="registerWmsReturnSigningSource")
    @PostMapping
    public ResponseEntity<WmsSignedReturnService.Source> register(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @RequestBody WmsSignedReturnService.Register body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.register(access.currentTenant(auth,request),
                access.authenticatedUser(auth),body));
    }
    @PostMapping("/{sourceId}/approve")
    public ResponseEntity<WmsSignedReturnService.Source> approve(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID sourceId,@RequestBody WmsSignedReturnService.Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),
                access.authenticatedUser(auth),sourceId,true,body.reason()));
    }
    @PostMapping("/{sourceId}/revoke")
    public ResponseEntity<WmsSignedReturnService.Source> revoke(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID sourceId,@RequestBody WmsSignedReturnService.Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),
                access.authenticatedUser(auth),sourceId,false,body.reason()));
    }
}

@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes/{intakeId}/signed-wms")
class WmsSignedReturnController {
    private final TenantAccessService access;
    private final WmsSignedReturnService service;
    WmsSignedReturnController(TenantAccessService access,WmsSignedReturnService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="listSignedLocalReturnEvidence")
    @GetMapping
    public ResponseEntity<List<WmsSignedReturnService.SignedReceipt>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.receipts(access.currentTenant(auth,request),marketplaceId,eventId,intakeId));
    }
    @Operation(operationId="importSignedLocalReturnEvidence")
    @PostMapping
    public ResponseEntity<WmsSignedReturnService.SignedReceipt> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,@RequestBody WmsSignedReturnService.SignedInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.importReceipt(access.currentTenant(auth,request),marketplaceId,eventId,
                intakeId,access.authenticatedUser(auth),body));
    }
}
