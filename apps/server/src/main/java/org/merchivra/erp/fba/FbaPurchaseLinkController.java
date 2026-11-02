package org.merchivra.erp.fba;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
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
    @GetMapping
    public ResponseEntity<List<FbaPurchaseLinkService.Linked>> links(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proof.links(access.currentTenant(auth,req),marketplaceId,shipmentId));
    }
    @GetMapping("/lines/{lineId}/candidates")
    public ResponseEntity<List<FbaPurchaseLinkService.Candidate>> candidates(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID shipmentId,@PathVariable UUID lineId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proof.candidates(access.currentTenant(auth,req),marketplaceId,shipmentId,lineId));
    }
    @PostMapping
    public FbaPurchaseLinkService.Linked propose(Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID shipmentId,
        @RequestBody FbaPurchaseLinkService.Propose body){
        return proof.propose(access.currentTenant(auth,req),marketplaceId,shipmentId,
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/{linkId}/confirm")
    public FbaPurchaseLinkService.Linked confirm(Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID shipmentId,
        @PathVariable UUID linkId,@RequestBody Reason body){
        // Verify link belongs to route's shipment, not merely same tenant/site.
        return proof.changeForShipment(access.currentTenant(auth,req),marketplaceId,shipmentId,
            access.authenticatedUser(auth),linkId,"CONFIRMED",body==null?null:body.reason());
    }
    @PostMapping("/{linkId}/revoke")
    public FbaPurchaseLinkService.Linked revoke(Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID shipmentId,
        @PathVariable UUID linkId,@RequestBody Reason body){
        return proof.changeForShipment(access.currentTenant(auth,req),marketplaceId,shipmentId,
            access.authenticatedUser(auth),linkId,"REVOKED",body==null?null:body.reason());
    }
}
