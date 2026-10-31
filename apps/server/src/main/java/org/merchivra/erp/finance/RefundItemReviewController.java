package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Owner-only manually attested refund disposition. No SP-API writes, stock
 * mutation, bank settlement, or financial ledger effect.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-items")
public class RefundItemReviewController {
    private final TenantAccessService access;
    private final RefundItemReviewService service;
    public RefundItemReviewController(TenantAccessService access,
        RefundItemReviewService service) {
        this.access=access;this.service=service;
    }
    public record ReviewBody(String itemId,int quantity,String disposition,
                             String evidenceReference) {}

    @GetMapping
    public ResponseEntity<RefundItemReviewService.Snapshot> snapshot(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.snapshot(access.currentTenant(authentication,request),
                marketplaceId,eventId));
    }
    @PostMapping
    public ResponseEntity<RefundItemReviewService.Snapshot> review(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @RequestBody ReviewBody body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.save(access.currentTenant(authentication,request),
                marketplaceId,access.authenticatedUser(authentication),eventId,
                body.itemId(),body.quantity(),body.disposition(),body.evidenceReference()));
    }
    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> withdraw(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable String itemId) {
        service.withdraw(access.currentTenant(authentication,request),marketplaceId,
            access.authenticatedUser(authentication),eventId,itemId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/audit")
    public ResponseEntity<List<RefundItemReviewService.Audit>> history(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(authentication,request),
                marketplaceId,eventId));
    }
}
