package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Only a MANUAL return cost exposure review, never a stock or GL posting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/refund-recovery")
public class RefundRecoveryController {
    private final TenantAccessService access;
    private final RefundRecoveryService service;
    public RefundRecoveryController(TenantAccessService access,RefundRecoveryService service){
        this.access=access;this.service=service;
    }
    public record Reason(String reason) {}
    @GetMapping
    public ResponseEntity<RefundRecoveryService.Status> preview(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.preview(access.currentTenant(auth,req),marketplaceId,eventId));
    }
    @GetMapping("/audit")
    public ResponseEntity<List<RefundRecoveryService.Audit>> audit(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,req),marketplaceId,eventId));
    }
    @PostMapping("/request")
    public ResponseEntity<RefundRecoveryService.Status> request(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.request(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),body.reason()));
    }
    @PostMapping("/approve")
    public ResponseEntity<RefundRecoveryService.Status> approve(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),true,body.reason()));
    }
    @PostMapping("/reject")
    public ResponseEntity<RefundRecoveryService.Status> reject(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId,@RequestBody Reason body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,req),marketplaceId,eventId,
                access.authenticatedUser(auth),false,body.reason()));
    }
}
