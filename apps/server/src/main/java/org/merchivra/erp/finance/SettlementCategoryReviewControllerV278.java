package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Local review records only. No mapping activation or accounting authorization. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/category-mapping-drafts/{mappingSha256}/reviews/{evidenceSha256}")
public class SettlementCategoryReviewControllerV278 {
    private final TenantAccessService tenants;
    private final SettlementCategoryReviewV276 reviews;
    public SettlementCategoryReviewControllerV278(TenantAccessService tenants,SettlementCategoryReviewV276 reviews) {
        this.tenants=tenants;this.reviews=reviews;
    }
    public record Reason(String reason) {}

    @GetMapping
    public ResponseEntity<SettlementCategoryReviewV276.Review> current(Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,@PathVariable String mappingSha256,
        @PathVariable String evidenceSha256) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.current(
            tenants.currentTenant(auth,req),marketplaceId,reportId,mappingSha256,evidenceSha256,tenants.authenticatedUser(auth)));
    }
    @PostMapping("/request")
    public ResponseEntity<SettlementCategoryReviewV276.Review> request(Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,@PathVariable String mappingSha256,
        @PathVariable String evidenceSha256,@RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.request(
            tenants.currentTenant(auth,req),marketplaceId,reportId,mappingSha256,evidenceSha256,tenants.authenticatedUser(auth),
            note==null?null:note.reason()));
    }
    @PostMapping("/approve")
    public ResponseEntity<SettlementCategoryReviewV276.Review> approve(Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,@PathVariable String mappingSha256,
        @PathVariable String evidenceSha256,@RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.approve(
            tenants.currentTenant(auth,req),marketplaceId,reportId,mappingSha256,evidenceSha256,tenants.authenticatedUser(auth),
            note==null?null:note.reason()));
    }

    // A stale RR transaction must be retried by reloading the evidence.
    // Do not translate unrelated integrity failures or claim success.
    @ExceptionHandler(org.springframework.dao.CannotSerializeTransactionException.class)
    public ResponseEntity<Void> concurrentSnapshot() {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build();
    }
}
