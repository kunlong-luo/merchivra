package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

/** Session / Owner / CSRF; no external certificates or Amazon/COGS writes. */
@RestController
@RequestMapping("/api/v1/finance")
public class CustodyIssuerAttestationControllerV208 {
    private final TenantAccessService access;
    private final CustodyIssuerAttestationServiceV208 service;
    public CustodyIssuerAttestationControllerV208(TenantAccessService access,
        CustodyIssuerAttestationServiceV208 service) {
        this.access=access;this.service=service;
    }

    @GetMapping("/custody-issuer-keys")
    public ResponseEntity<List<CustodyIssuerAttestationServiceV208.Issuer>> keys(
        Authentication auth,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.issuers(access.currentTenant(auth,request)));
    }

    @PostMapping("/custody-issuer-keys")
    public ResponseEntity<CustodyIssuerAttestationServiceV208.Issuer> register(
        Authentication auth,HttpServletRequest request,
        @RequestBody CustodyIssuerAttestationServiceV208.RegisterIssuer input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.register(access.currentTenant(auth,request),
                access.authenticatedUser(auth),input));
    }

    @PostMapping("/custody-issuer-keys/{issuerId}/review")
    public ResponseEntity<CustodyIssuerAttestationServiceV208.Issuer> review(
        Authentication auth,HttpServletRequest request,@PathVariable UUID issuerId,
        @RequestBody CustodyIssuerAttestationServiceV208.Decision input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.reviewIssuer(access.currentTenant(auth,request),
                issuerId,access.authenticatedUser(auth),input));
    }

    @PostMapping("/custody-issuer-keys/{issuerId}/revoke")
    public ResponseEntity<CustodyIssuerAttestationServiceV208.Issuer> revoke(
        Authentication auth,HttpServletRequest request,@PathVariable UUID issuerId,
        @RequestBody CustodyIssuerAttestationServiceV208.Decision input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.revokeIssuer(access.currentTenant(auth,request),
                issuerId,access.authenticatedUser(auth),input));
    }

    @GetMapping("/marketplaces/{marketplaceId}/orders/{orderId}/custody-issuer-integrity")
    public ResponseEntity<CustodyIssuerAttestationServiceV208.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,request),marketplaceId,orderId));
    }

    @PostMapping("/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/claimed-issuer-signatures")
    public ResponseEntity<CustodyIssuerAttestationServiceV208.SignedProof> signed(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID dossierId,@PathVariable String kind,
        @RequestBody CustodyIssuerAttestationServiceV208.SignedInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.submit(access.currentTenant(auth,request),
                marketplaceId,orderId,dossierId,kind,access.authenticatedUser(auth),input));
    }
}
