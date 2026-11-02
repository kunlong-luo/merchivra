package org.merchivra.erp.fba;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V76: read-only bounded, manually evidenced FBA/PO/ASN discrepancy board. */
@RestController
@RequestMapping("/api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation")
public class FbaPurchaseReconciliationController {
    private final TenantAccessService access;
    private final FbaPurchaseReconciliationService service;
    public FbaPurchaseReconciliationController(TenantAccessService access,
                                               FbaPurchaseReconciliationService service){
        this.access=access;this.service=service;
    }
    @GetMapping
    public ResponseEntity<FbaPurchaseReconciliationService.Overview> overview(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.overview(access.currentTenant(authentication,request),
                                   marketplaceId));
    }
}
