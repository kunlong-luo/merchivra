package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner Session-scoped read-only scorecards and manual local PO follow-up workflow. */
@RestController
@RequestMapping("/api/v1/purchasing/supplier-performance")
public class SupplierPerformanceController {
    private final SupplierPerformanceService performance;
    private final PurchaseFollowupService followups;
    private final TenantAccessService access;
    public SupplierPerformanceController(SupplierPerformanceService performance,
        PurchaseFollowupService followups,TenantAccessService access) {
        this.performance=performance;this.followups=followups;this.access=access;
    }
    public record StateBody(String state) {}
    @GetMapping("/scorecards")
    public List<SupplierPerformanceService.Scorecard> scorecards(
        Authentication auth,HttpServletRequest req) {
        return performance.scorecards(access.currentTenant(auth,req));
    }
    @GetMapping("/followups")
    public List<PurchaseFollowupService.Finding> followups(
        Authentication auth,HttpServletRequest req,
        @RequestParam(defaultValue="ACTIVE") String filter) {
        return followups.findings(access.currentTenant(auth,req),filter);
    }
    @PostMapping("/scan")
    public PurchaseFollowupService.ScanResult scan(Authentication auth,HttpServletRequest req) {
        return followups.scan(access.currentTenant(auth,req),
            access.authenticatedUser(auth),"MANUAL");
    }
    @PostMapping("/followups/{findingId}/state")
    public PurchaseFollowupService.FindingChange change(
        Authentication auth,HttpServletRequest req,@PathVariable UUID findingId,
        @RequestBody StateBody body) {
        return followups.changeState(access.currentTenant(auth,req),
            access.authenticatedUser(auth),findingId,body==null?null:body.state());
    }
}
