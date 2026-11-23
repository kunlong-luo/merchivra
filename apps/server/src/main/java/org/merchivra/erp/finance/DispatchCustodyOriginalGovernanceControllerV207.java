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

/** Owner + CSRF; no original download, hold release, disposal or posting. */
@RestController
@RequestMapping("/api/v1/finance")
public class DispatchCustodyOriginalGovernanceControllerV207 {
    private final TenantAccessService access;
    private final DispatchCustodyOriginalGovernanceServiceV207 service;

    public DispatchCustodyOriginalGovernanceControllerV207(
        TenantAccessService access,DispatchCustodyOriginalGovernanceServiceV207 service) {
        this.access=access;this.service=service;
    }

    @GetMapping("/custody-originals/capacity")
    public ResponseEntity<DispatchCustodyOriginalGovernanceServiceV207.Capacity> capacity(
        Authentication auth,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.capacity(access.currentTenant(auth,request)));
    }

    @GetMapping("/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/governance")
    public ResponseEntity<DispatchCustodyOriginalGovernanceServiceV207.Metadata> metadata(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID dossierId,@PathVariable String kind) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.viewAndAudit(access.currentTenant(auth,request),
                marketplaceId,orderId,dossierId,kind,access.authenticatedUser(auth)));
    }

    @PostMapping("/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/retention-proposals")
    public ResponseEntity<DispatchCustodyOriginalGovernanceServiceV207.Metadata> propose(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID dossierId,@PathVariable String kind,
        @RequestBody DispatchCustodyOriginalGovernanceServiceV207.RetentionInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.propose(access.currentTenant(auth,request),
                marketplaceId,orderId,dossierId,kind,access.authenticatedUser(auth),input));
    }

    @PostMapping("/marketplaces/{marketplaceId}/orders/{orderId}/custody-dossiers/{dossierId}/originals/{kind}/retention-proposals/{proposalId}/review")
    public ResponseEntity<DispatchCustodyOriginalGovernanceServiceV207.Metadata> review(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId,
        @PathVariable UUID dossierId,@PathVariable String kind,@PathVariable UUID proposalId,
        @RequestBody DispatchCustodyOriginalGovernanceServiceV207.DecisionInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.review(access.currentTenant(auth,request),
                marketplaceId,orderId,dossierId,kind,proposalId,
                access.authenticatedUser(auth),input));
    }
}
