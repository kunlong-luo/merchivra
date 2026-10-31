package org.merchivra.erp.catalogsync;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner + CSRF; no browser-selected Seller, URL, token or record payload. */
@RestController
@RequestMapping("/api/v1/catalog-sync")
@ConditionalOnProperty(prefix="merchivra.catalog-sync",name="enabled",havingValue="true")
public class CatalogSyncController {
    private final TenantAccessService access;
    private final CatalogSyncService sync;
    public CatalogSyncController(TenantAccessService access,CatalogSyncService sync){
        this.access=access;this.sync=sync;
    }
    @GetMapping("/{marketplaceId}/{stream}")
    public CatalogSyncService.Status status(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable CatalogSyncService.Stream stream) {
        return sync.status(access.currentTenant(auth,request),marketplaceId,stream);
    }
    @PostMapping("/{marketplaceId}/{stream}/start")
    public CatalogSyncService.Status start(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable CatalogSyncService.Stream stream){
        return sync.start(access.currentTenant(auth,request),marketplaceId,stream);
    }
    @PostMapping("/{marketplaceId}/{stream}/step")
    public CatalogSyncService.Status step(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable CatalogSyncService.Stream stream){
        return sync.step(access.currentTenant(auth,request),marketplaceId,stream);
    }
}
