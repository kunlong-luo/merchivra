package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Human-reviewed LIVE Finances attribution, never an Amazon mutation. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/item-allocations/{eventId}")
public class ItemAllocationController {
    private final TenantAccessService access;
    private final ItemAllocationService service;
    public ItemAllocationController(TenantAccessService access,ItemAllocationService service) {
        this.access=access;this.service=service;
    }
    public record LineChange(String itemId,String category,String signedAmount,
        String currency,String evidenceReference,String reason) {}
    public record Reason(String reason) {}
    @GetMapping
    public ResponseEntity<ItemAllocationService.View> preview(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,req),marketplaceId,eventId));
    }
    @GetMapping("/audit")
    public ResponseEntity<List<ItemAllocationService.Audit>> history(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(auth,req),marketplaceId,eventId));
    }
    @PutMapping("/line")
    public ResponseEntity<ItemAllocationService.View> save(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody LineChange body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.save(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),body.itemId(),body.category(),
                body.signedAmount(),body.currency(),body.evidenceReference(),body.reason()));
    }
    @PostMapping("/submit")
    public ResponseEntity<ItemAllocationService.View> submit(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.submit(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),body.reason()));
    }
    @PostMapping("/approve")
    public ResponseEntity<ItemAllocationService.View> approve(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.decide(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),true,body.reason()));
    }
    @PostMapping("/reject")
    public ResponseEntity<ItemAllocationService.View> reject(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.decide(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),false,body.reason()));
    }
    @PostMapping("/reopen")
    public ResponseEntity<ItemAllocationService.View> reopen(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.reopen(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),body.reason()));
    }
}
