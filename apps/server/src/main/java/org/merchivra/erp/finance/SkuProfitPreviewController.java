package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** Owner-only page-specific provisional SKU and Marketplace contributions. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/sku-profit-preview")
public class SkuProfitPreviewController {
    private final TenantAccessService access;
    private final SkuProfitPreviewService service;
    public SkuProfitPreviewController(TenantAccessService access,
        SkuProfitPreviewService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getSkuProfitPreview")
    @GetMapping
    public ResponseEntity<SkuProfitPreviewService.Preview> preview(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,@PathVariable UUID marketplaceId,
        @RequestParam LocalDate after,@RequestParam LocalDate before,
        @RequestParam(defaultValue="0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,req),
                marketplaceId,after,before,offset));
    }
    @Operation(operationId="exportSkuProfitPreviewCsv")
    @GetMapping(value="/export.csv",produces="text/csv")
    public ResponseEntity<String> export(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest req,@PathVariable UUID marketplaceId,
        @RequestParam LocalDate after,@RequestParam LocalDate before,
        @RequestParam(defaultValue="0") int offset) {
        var data=service.preview(access.currentTenant(auth,req),marketplaceId,
            after,before,offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"merchivra-provisional-sku-profit-page.csv\"")
            .contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
            .body(SkuProfitCsv.export(data));
    }
}
