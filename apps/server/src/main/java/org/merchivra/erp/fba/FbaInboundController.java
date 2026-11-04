package org.merchivra.erp.fba;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V65: owner-session-local shipment plans and manual/mock evidence; never Amazon writes. */
@RestController
@RequestMapping("/api/v1/fba/marketplaces/{marketplaceId}/shipments")
public class FbaInboundController {
    private final TenantAccessService access;
    private final FbaInboundService service;
    public FbaInboundController(TenantAccessService access,FbaInboundService service){
        this.access=access;this.service=service;
    }
    @Operation(operationId="listFbaInboundShipments")
    @GetMapping
    public ResponseEntity<List<FbaInboundService.Shipment>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(access.currentTenant(auth,request),marketplaceId));
    }
    @Operation(operationId="listFbaInboundShipmentPage")
    @GetMapping("/page")
    public ResponseEntity<FbaInboundService.ShipmentPage> page(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="ALL") String state,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.page(access.currentTenant(auth,request),marketplaceId,
                limit,search,state,cursor));
    }
    @Operation(operationId="listFbaInboundTransit")
    @GetMapping("/in-transit")
    public ResponseEntity<List<FbaInboundService.TransitRow>> transit(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.transit(access.currentTenant(auth,request),marketplaceId));
    }
    @Operation(operationId="getFbaInboundShipment")
    @GetMapping("/{shipmentId}")
    public ResponseEntity<FbaInboundService.Detail> get(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.detail(access.currentTenant(auth,request),marketplaceId,shipmentId));
    }
    @Operation(operationId="createFbaInboundShipment")
    @PostMapping
    public ResponseEntity<FbaInboundService.Detail> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaInboundService.Create requestBody){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.create(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),requestBody));
    }
    @Operation(operationId="dispatchFbaInboundShipment")
    @PostMapping("/{shipmentId}/dispatch")
    public ResponseEntity<FbaInboundService.Detail> dispatch(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.dispatch(access.currentTenant(auth,request),marketplaceId,shipmentId));
    }
    @Operation(operationId="recordFbaInboundReceipt")
    @PostMapping("/{shipmentId}/receipts")
    public ResponseEntity<FbaInboundService.Detail> receipt(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId,@RequestBody FbaInboundService.Receipt body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.receipt(access.currentTenant(auth,request),marketplaceId,shipmentId,
                access.authenticatedUser(auth),body));
    }
    @Operation(operationId="startFbaInboundReview")
    @PostMapping("/{shipmentId}/review")
    public ResponseEntity<FbaInboundService.Detail> review(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.startReview(access.currentTenant(auth,request),marketplaceId,shipmentId));
    }
    @Operation(operationId="resolveFbaInboundVariance")
    @PostMapping("/{shipmentId}/variances")
    public ResponseEntity<FbaInboundService.Detail> resolve(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId,@RequestBody FbaInboundService.Resolve body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.resolve(access.currentTenant(auth,request),marketplaceId,shipmentId,
                access.authenticatedUser(auth),body));
    }
    @Operation(operationId="finalizeFbaInboundReconciliation")
    @PostMapping("/{shipmentId}/reconcile")
    public ResponseEntity<FbaInboundService.Detail> reconcile(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.finalizeReview(access.currentTenant(auth,request),marketplaceId,shipmentId));
    }
}
