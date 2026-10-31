package org.merchivra.erp.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** OWNER-only catalog operations. Browser never selects tenant or account. */
@RestController
@RequestMapping("/api/v1/catalog")
public class ProductCatalogController {
    private final TenantAccessService access;
    private final ProductCatalogService catalog;
    public ProductCatalogController(TenantAccessService access,ProductCatalogService catalog) {
        this.access=access;this.catalog=catalog;
    }
    public record NewSku(String internalSku,String displayName) {}
    public record EditSku(String displayName,String status) {}
    public record LinkSku(UUID productSkuId) {}

    @GetMapping("/skus")
    public List<ProductCatalogService.Sku> skus(Authentication auth,HttpServletRequest req,
        @RequestParam(required=false) String prefix,
        @RequestParam(required=false) String after,
        @RequestParam(defaultValue="50") int limit) {
        return catalog.list(access.currentTenant(auth,req),prefix,after,limit);
    }
    @PostMapping("/skus")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductCatalogService.Sku create(Authentication auth,HttpServletRequest req,
        @RequestBody NewSku body) {
        return catalog.create(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body.internalSku(),body.displayName());
    }
    @PatchMapping("/skus/{id}")
    public ProductCatalogService.Sku edit(Authentication auth,HttpServletRequest req,
        @PathVariable UUID id,@RequestBody EditSku body) {
        return catalog.update(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,body.displayName(),body.status());
    }
    @PostMapping("/listings/{listingId}/map")
    public ProductCatalogService.Mapping link(Authentication auth,HttpServletRequest req,
        @PathVariable UUID listingId,@RequestBody LinkSku body) {
        return catalog.mapListing(access.currentTenant(auth,req),
            access.authenticatedUser(auth),listingId,body.productSkuId());
    }
    @DeleteMapping("/listings/{listingId}/map")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(Authentication auth,HttpServletRequest req,@PathVariable UUID listingId) {
        catalog.unmapListing(access.currentTenant(auth,req),
            access.authenticatedUser(auth),listingId);
    }
}
