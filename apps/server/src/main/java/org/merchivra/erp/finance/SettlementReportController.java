package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only settlement report staging, no public upload or URL fetch API. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/reports")
public class SettlementReportController {
    private final TenantAccessService tenants;
    private final SettlementReportImportService imports;
    private final SettlementReportComparisonService comparisons;

    public SettlementReportController(TenantAccessService tenants,
        SettlementReportImportService imports,SettlementReportComparisonService comparisons) {
        this.tenants=tenants;this.imports=imports;this.comparisons=comparisons;
    }

    @GetMapping
    public ResponseEntity<List<SettlementReportImportService.Imported>> list(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(imports.list(tenants.currentTenant(auth,req),marketplaceId));
    }

    @GetMapping("/{reportId}/lines")
    public ResponseEntity<List<SettlementReportImportService.Line>> lines(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(imports.lines(tenants.currentTenant(auth,req),marketplaceId,reportId));
    }

    @GetMapping("/{reportId}/finance-comparison")
    public ResponseEntity<SettlementReportComparisonService.Comparison> comparison(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(comparisons.compare(tenants.currentTenant(auth,req),marketplaceId,reportId));
    }

    @GetMapping("/{reportId}/review")
    public ResponseEntity<SettlementReportImportService.Review> review(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(imports.review(tenants.currentTenant(auth,req),marketplaceId,reportId));
    }
}
