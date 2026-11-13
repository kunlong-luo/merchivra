package org.merchivra.erp.fba;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.merchivra.erp.purchasing.LocalKeysetPage;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit local PO/ASN provenance only; not Amazon confirmation. */
@RestController
@RequestMapping("/api/v1/fba/marketplaces/{marketplaceId}/shipments/{shipmentId}/purchase-links")
public class FbaPurchaseLinkController {
    private final FbaPurchaseLinkService proof;
    private final TenantAccessService access;
    public FbaPurchaseLinkController(FbaPurchaseLinkService proof,TenantAccessService access){
        this.proof=proof;this.access=access;
    }
    public record Reason(String reason) {}
    @Operation(operationId="listFbaPurchaseLinks")
    @GetMapping
    public ResponseEntity<List<FbaPurchaseLinkService.Linked>> links(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proof.links(access.currentTenant(auth,req),marketplaceId,shipmentId));
    }
    @Operation(operationId="listFbaPurchaseCandidates")
    @GetMapping("/lines/{lineId}/candidates")
    public ResponseEntity<List<FbaPurchaseLinkService.Candidate>> candidates(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId,@PathVariable UUID lineId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proof.candidates(access.currentTenant(auth,req),marketplaceId,shipmentId,lineId));
    }
    /** V147: all tenant- and shipment-scoped local identity proof rows. */
    @Operation(operationId="listFbaPurchaseLinksPage")
    @GetMapping("/page")
    public ResponseEntity<LocalKeysetPage.Page<FbaPurchaseLinkService.Linked>> linksPage(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="ALL") String state,
        @RequestParam(required=false) UUID inboundLineId,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proof.linksPage(access.currentTenant(auth,req),marketplaceId,shipmentId,
                inboundLineId,state,limit,cursor));
    }

    /** V147: approved PO candidates, each distinct optional SHIPPED ASN row included. */
    @Operation(operationId="listFbaPurchaseCandidatesPage")
    @GetMapping("/lines/{lineId}/candidates/page")
    public ResponseEntity<LocalKeysetPage.Page<FbaPurchaseLinkService.Candidate>> candidatesPage(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId,@PathVariable UUID lineId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) UUID purchaseLineId,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proof.candidatesPage(access.currentTenant(auth,req),marketplaceId,shipmentId,
                lineId,purchaseLineId,limit,cursor));
    }

    @Operation(operationId="proposeFbaPurchaseLink")
    @PostMapping
    public FbaPurchaseLinkService.Linked propose(@Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID shipmentId,
        @RequestBody FbaPurchaseLinkService.Propose body){
        return proof.propose(access.currentTenant(auth,req),marketplaceId,shipmentId,
            access.authenticatedUser(auth),body);
    }
    @Operation(operationId="confirmFbaPurchaseLink")
    @PostMapping("/{linkId}/confirm")
    public FbaPurchaseLinkService.Linked confirm(@Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID shipmentId,
        @PathVariable UUID linkId,@RequestBody Reason body){
        // Verify link belongs to route's shipment, not merely same tenant/site.
        return proof.changeForShipment(access.currentTenant(auth,req),marketplaceId,shipmentId,
            access.authenticatedUser(auth),linkId,"CONFIRMED",body==null?null:body.reason());
    }
    @Operation(operationId="revokeFbaPurchaseLink")
    @PostMapping("/{linkId}/revoke")
    public FbaPurchaseLinkService.Linked revoke(@Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID shipmentId,
        @PathVariable UUID linkId,@RequestBody Reason body){
        return proof.changeForShipment(access.currentTenant(auth,req),marketplaceId,shipmentId,
            access.authenticatedUser(auth),linkId,"REVOKED",body==null?null:body.reason());
    }
}
