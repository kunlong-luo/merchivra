package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Manual signed product-refund attribution only. No Amazon writes or COGS. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-allocations")
public class RefundAmountAllocationController {
    private final TenantAccessService access;
    private final RefundAmountAllocationService service;
    public RefundAmountAllocationController(TenantAccessService access,
        RefundAmountAllocationService service) {
        this.access=access;this.service=service;
    }
    public record Body(String itemId,String signedAmount,String currency,
        String evidenceReference) {}
    @GetMapping
    public ResponseEntity<RefundAmountAllocationService.View> view(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.view(access.currentTenant(auth,request),marketplaceId,eventId));
    }
    @PostMapping
    public ResponseEntity<RefundAmountAllocationService.View> upsert(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Body body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.save(access.currentTenant(auth,request),marketplaceId,eventId,
                access.authenticatedUser(auth),body.itemId(),body.signedAmount(),
                body.currency(),body.evidenceReference()));
    }
    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> withdraw(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@PathVariable String itemId) {
        service.withdraw(access.currentTenant(auth,request),marketplaceId,eventId,
            access.authenticatedUser(auth),itemId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/audit")
    public ResponseEntity<List<RefundAmountAllocationService.Audit>> audit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(auth,request),marketplaceId,eventId));
    }
}
