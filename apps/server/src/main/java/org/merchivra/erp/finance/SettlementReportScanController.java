package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner Session + CSRF; only an opaque Marketplace UUID is accepted. */
@RestController
@RequestMapping("/api/v1/settlement-report-scan")
@ConditionalOnProperty(prefix="merchivra.spapi.reports",name="enabled",havingValue="true")
public class SettlementReportScanController {
    private final TenantAccessService access;
    private final SettlementReportScanService jobs;
    public SettlementReportScanController(TenantAccessService access,
        SettlementReportScanService jobs) {
        this.access=access;this.jobs=jobs;
    }
    @GetMapping("/{marketplaceId}")
    public ResponseEntity<SettlementReportScanService.Status> status(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(jobs.status(access.currentTenant(auth,request),marketplaceId));
    }
    @PostMapping("/{marketplaceId}/start")
    public ResponseEntity<SettlementReportScanService.Status> start(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(jobs.start(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth)));
    }
    @PostMapping("/{marketplaceId}/step")
    public ResponseEntity<SettlementReportScanService.Status> step(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(jobs.step(access.currentTenant(auth,request),marketplaceId));
    }
}
