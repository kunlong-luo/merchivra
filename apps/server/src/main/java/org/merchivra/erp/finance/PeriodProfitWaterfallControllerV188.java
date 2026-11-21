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

/** V188 Owner-only cost waterfall for an existing V64 frozen job. Read-only. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/cost-waterfall-preview/{jobId}")
public class PeriodProfitWaterfallControllerV188 {
    private final TenantAccessService access;
    private final PeriodProfitWaterfallServiceV188 service;
    public PeriodProfitWaterfallControllerV188(TenantAccessService access,
        PeriodProfitWaterfallServiceV188 service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="previewV188UnverifiedFullCostPeriodWaterfall")
    @GetMapping
    public ResponseEntity<PeriodProfitWaterfallV188.Preview> preview(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.preview(access.currentTenant(auth,request),marketplaceId,jobId));
    }
}
