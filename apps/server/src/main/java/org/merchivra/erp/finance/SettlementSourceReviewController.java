package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Scoped Owner session + CSRF; human source review is NOT a bank settlement. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/source-review")
public class SettlementSourceReviewController {
    private final TenantAccessService access;
    private final SettlementSourceReviewService reviews;
    public SettlementSourceReviewController(TenantAccessService access,
        SettlementSourceReviewService reviews) {
        this.access=access;this.reviews=reviews;
    }
    public record Reason(String reason) {}

    @GetMapping
    public ResponseEntity<SettlementSourceReviewService.Review> status(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(reviews.status(access.currentTenant(auth,req),marketplaceId,reportId));
    }
    @PostMapping("/request")
    public ResponseEntity<SettlementSourceReviewService.Review> request(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(reviews.request(access.currentTenant(auth,req),marketplaceId,reportId,
                access.authenticatedUser(auth),note.reason()));
    }
    @PostMapping("/approve")
    public ResponseEntity<SettlementSourceReviewService.Review> approve(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(reviews.decide(access.currentTenant(auth,req),marketplaceId,reportId,
                access.authenticatedUser(auth),true,note.reason()));
    }
    @PostMapping("/reject")
    public ResponseEntity<SettlementSourceReviewService.Review> reject(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(reviews.decide(access.currentTenant(auth,req),marketplaceId,reportId,
                access.authenticatedUser(auth),false,note.reason()));
    }
    @GetMapping("/audit")
    public ResponseEntity<List<SettlementSourceReviewService.Audit>> history(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(reviews.history(access.currentTenant(auth,req),marketplaceId,reportId));
    }
}
