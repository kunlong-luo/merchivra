package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** V171 immutable MANUAL return receipt evidence, never stock or GL posting. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-receipts")
public class ReturnReceiptEvidenceController {
    private final TenantAccessService access;
    private final ReturnReceiptEvidenceService service;
    public ReturnReceiptEvidenceController(TenantAccessService access,
        ReturnReceiptEvidenceService service) {
        this.access=access;this.service=service;
    }
    public record Decision(String reason) {}
    @Operation(operationId="listManualReturnReceiptClaims")
    @GetMapping
    public ResponseEntity<List<ReturnReceiptEvidenceService.Claim>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.list(access.currentTenant(auth,request),marketplaceId,eventId));
    }
    @Operation(operationId="requestManualReturnReceiptClaim")
    @PostMapping
    public ResponseEntity<ReturnReceiptEvidenceService.Claim> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @RequestBody ReturnReceiptEvidenceService.Request body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.request(access.currentTenant(auth,request),marketplaceId,eventId,
                access.authenticatedUser(auth),body));
    }
    @Operation(operationId="decideManualReturnReceiptClaim")
    @PostMapping("/{claimId}/approve")
    public ResponseEntity<ReturnReceiptEvidenceService.Claim> approve(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID claimId,@RequestBody Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,
                eventId,claimId,access.authenticatedUser(auth),true,body.reason()));
    }
    @PostMapping("/{claimId}/reject")
    public ResponseEntity<ReturnReceiptEvidenceService.Claim> reject(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID claimId,@RequestBody Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,
                eventId,claimId,access.authenticatedUser(auth),false,body.reason()));
    }
    @GetMapping("/{claimId}/audit")
    public ResponseEntity<List<ReturnReceiptEvidenceService.Audit>> audit(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID claimId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,
                eventId,claimId));
    }
}
