package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** Owner-only, CSRF-protected manual QC evidence triage; never a physical receipt. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes/{intakeId}/wms-qc-review")
public class WmsReturnQcReviewController {
    private final TenantAccessService access;
    private final WmsReturnQcReviewService service;
    public WmsReturnQcReviewController(TenantAccessService access,
        WmsReturnQcReviewService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getWmsReturnQcReview")
    @GetMapping
    public ResponseEntity<WmsReturnQcReviewService.Review> get(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),
                marketplaceId,eventId,intakeId));
    }
    @Operation(operationId="requestWmsReturnQcReview")
    @PostMapping
    public ResponseEntity<WmsReturnQcReviewService.Review> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,
        @RequestBody WmsReturnQcReviewService.Request body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.request(access.currentTenant(auth,request),
                marketplaceId,eventId,intakeId,access.authenticatedUser(auth),body));
    }
    @Operation(operationId="acknowledgeWmsReturnQcReview")
    @PostMapping("/{caseId}/acknowledge")
    public ResponseEntity<WmsReturnQcReviewService.Review> acknowledge(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,@PathVariable UUID caseId,
        @RequestBody WmsReturnQcReviewService.Decision body) {
        return decision(auth,request,marketplaceId,eventId,intakeId,caseId,true,body);
    }
    @Operation(operationId="rejectWmsReturnQcReview")
    @PostMapping("/{caseId}/reject")
    public ResponseEntity<WmsReturnQcReviewService.Review> reject(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,@PathVariable UUID caseId,
        @RequestBody WmsReturnQcReviewService.Decision body) {
        return decision(auth,request,marketplaceId,eventId,intakeId,caseId,false,body);
    }
    private ResponseEntity<WmsReturnQcReviewService.Review> decision(
        Authentication auth,HttpServletRequest request,UUID mp,UUID event,
        UUID intake,UUID caseId,boolean acknowledged,WmsReturnQcReviewService.Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),mp,event,intake,caseId,
                access.authenticatedUser(auth),acknowledged,body));
    }
}
