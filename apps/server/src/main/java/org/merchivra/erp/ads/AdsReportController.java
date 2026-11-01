package org.merchivra.erp.ads;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-only, local Ads report evidence. No Ads OAuth callback or Ads API calls. */
@RestController
@RequestMapping("/api/v1/ads/marketplaces/{marketplaceId}")
public class AdsReportController {
    private final TenantAccessService access;
    private final AdsReportService service;
    public AdsReportController(TenantAccessService access,AdsReportService service){
        this.access=access;this.service=service;
    }
    @Operation(operationId="getAdsAuthorizationCapability")
    @GetMapping("/authorization")
    public ResponseEntity<AdsReportService.Capability> authorization(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId){
        // Current owner scope and marketplace must still be enforced.
        service.profiles(access.currentTenant(auth,req),marketplaceId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.capability());
    }
    @Operation(operationId="listAdsProfiles")
    @GetMapping("/profiles")
    public ResponseEntity<List<AdsReportService.Profile>> profiles(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.profiles(access.currentTenant(auth,req),marketplaceId));
    }
    @Operation(operationId="registerAdsProfile")
    @PostMapping("/profiles")
    public ResponseEntity<AdsReportService.Profile> register(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@RequestBody AdsReportService.ProfileInput body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.register(
            access.currentTenant(auth,req),marketplaceId,access.authenticatedUser(auth),body));
    }
    @Operation(operationId="listAdsReports")
    @GetMapping("/reports")
    public ResponseEntity<List<AdsReportService.Batch>> reports(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.reports(access.currentTenant(auth,req),marketplaceId));
    }
    @Operation(operationId="importAdsReport")
    @PostMapping("/reports")
    public ResponseEntity<AdsReportService.Detail> importReport(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@RequestBody AdsReportService.ImportInput body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.importReport(
            access.currentTenant(auth,req),marketplaceId,access.authenticatedUser(auth),body));
    }
    @Operation(operationId="getAdsReport")
    @GetMapping("/reports/{reportId}")
    public ResponseEntity<AdsReportService.Detail> detail(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.report(access.currentTenant(auth,req),marketplaceId,reportId));
    }
    @Operation(operationId="reviewAdsReport")
    @PostMapping("/reports/{reportId}/review")
    public ResponseEntity<AdsReportService.Detail> review(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @RequestBody AdsReportService.Review body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.review(
            access.currentTenant(auth,req),marketplaceId,access.authenticatedUser(auth),reportId,body));
    }
    @Operation(operationId="listReviewedAdsCosts")
    @GetMapping("/reviewed-costs")
    public ResponseEntity<List<AdsReportService.Daily>> daily(
        @Parameter(hidden=true) Authentication auth,@Parameter(hidden=true) HttpServletRequest req,
        @PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.daily(access.currentTenant(auth,req),marketplaceId));
    }
}
