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

    /** Ephemeral local mapping, Owner/CSRF, no persistence or approval. */
    @org.springframework.web.bind.annotation.PostMapping("/{reportId}/category-comparison-preview")
    public ResponseEntity<SettlementCategoryPreviewV273.Preview> categoryPreview(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID reportId,
        @org.springframework.web.bind.annotation.RequestBody List<SettlementCategoryPreviewV273.Mapping> mapping) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(comparisons.previewCategories(tenants.currentTenant(auth,req),marketplaceId,reportId,mapping));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{reportId}/category-mapping-drafts")
    public ResponseEntity<SettlementReportComparisonService.MappingDraft> saveCategoryMapping(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @org.springframework.web.bind.annotation.RequestBody List<SettlementCategoryPreviewV273.Mapping> mapping) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comparisons.saveCategoryMapping(
            tenants.currentTenant(auth,req),marketplaceId,reportId,tenants.authenticatedUser(auth),mapping));
    }

    @GetMapping("/{reportId}/category-mapping-drafts/{mappingSha256}")
    public ResponseEntity<SettlementReportComparisonService.MappingDraft> readCategoryMapping(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @PathVariable String mappingSha256) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comparisons.readCategoryMapping(
            tenants.currentTenant(auth,req),marketplaceId,reportId,mappingSha256));
    }

    @GetMapping("/{reportId}/category-mapping-drafts/{mappingSha256}/preview")
    public ResponseEntity<SettlementReportComparisonService.SavedMappingPreview> previewSavedCategoryMapping(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,@PathVariable UUID reportId,
        @PathVariable String mappingSha256) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comparisons.previewSavedCategoryMapping(
            tenants.currentTenant(auth,req),marketplaceId,reportId,mappingSha256));
    }

    @GetMapping("/{reportId}/review")
    public ResponseEntity<SettlementReportImportService.Review> review(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(imports.review(tenants.currentTenant(auth,req),marketplaceId,reportId));
    }
}
