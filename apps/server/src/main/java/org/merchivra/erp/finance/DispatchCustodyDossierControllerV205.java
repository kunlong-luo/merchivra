package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V205: Owner-only, CSRF-protected untrusted external-document dossier. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class DispatchCustodyDossierControllerV205 {
    private final TenantAccessService access;
    private final DispatchCustodyDossierServiceV205 service;

    public DispatchCustodyDossierControllerV205(TenantAccessService access,
        DispatchCustodyDossierServiceV205 service) {
        this.access=access;this.service=service;
    }

    @GetMapping("/custody-readiness")
    public ResponseEntity<DispatchCustodyDossierServiceV205.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.preview(access.currentTenant(auth,request),marketplaceId,orderId));
    }

    @PostMapping("/signed-dispatch/{signedDispatchId}/custody-dossiers")
    public ResponseEntity<DispatchCustodyDossierServiceV205.Dossier> submit(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID signedDispatchId,
        @RequestBody DispatchCustodyDossierServiceV205.Submit body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.submit(access.currentTenant(auth,request),marketplaceId,
                orderId,signedDispatchId,access.authenticatedUser(auth),body));
    }

    @PostMapping("/custody-dossiers/{dossierId}/review")
    public ResponseEntity<DispatchCustodyDossierServiceV205.Dossier> review(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID dossierId,
        @RequestBody DispatchCustodyDossierServiceV205.ReviewInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.review(access.currentTenant(auth,request),marketplaceId,
                orderId,dossierId,access.authenticatedUser(auth),body));
    }
}
