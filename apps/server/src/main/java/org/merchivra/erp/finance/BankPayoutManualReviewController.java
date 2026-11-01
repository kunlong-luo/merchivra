package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Manual reference reviewed only, NEVER bank-paid/ledger state. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/reports/{reportId}/manual-bank-review")
public class BankPayoutManualReviewController {
    private final TenantAccessService access;
    private final BankPayoutManualReviewService service;
    public record Reason(String reason) {}
    public BankPayoutManualReviewController(TenantAccessService access,
        BankPayoutManualReviewService service) {
        this.access=access;this.service=service;
    }
    @GetMapping
    public ResponseEntity<BankPayoutManualReviewService.Status> status(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.status(access.currentTenant(auth,req),marketplaceId,reportId));
    }
    @GetMapping("/audit")
    public ResponseEntity<List<BankPayoutManualReviewService.Audit>> history(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(auth,req),marketplaceId,reportId));
    }
    @PostMapping("/request")
    public ResponseEntity<BankPayoutManualReviewService.Status> request(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.request(access.currentTenant(auth,req),marketplaceId,
                reportId,access.authenticatedUser(auth),note.reason()));
    }
    @PostMapping("/approve")
    public ResponseEntity<BankPayoutManualReviewService.Status> approve(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.decide(access.currentTenant(auth,req),marketplaceId,
                reportId,access.authenticatedUser(auth),true,note.reason()));
    }
    @PostMapping("/reject")
    public ResponseEntity<BankPayoutManualReviewService.Status> reject(
        Authentication auth,HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody Reason note) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.decide(access.currentTenant(auth,req),marketplaceId,
                reportId,access.authenticatedUser(auth),false,note.reason()));
    }
}
