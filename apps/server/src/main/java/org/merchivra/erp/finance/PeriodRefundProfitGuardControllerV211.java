package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped, no-store, read-only V188/V210 period refund bridge. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/period-refund-profit-guard")
public class PeriodRefundProfitGuardControllerV211 {
    private final TenantAccessService access;
    private final PeriodRefundProfitGuardServiceV211 service;

    public PeriodRefundProfitGuardControllerV211(TenantAccessService access,
        PeriodRefundProfitGuardServiceV211 service) {
        this.access=access;
        this.service=service;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<PeriodRefundProfitGuardV211.Result> preview(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(
                access.currentTenant(authentication,request),marketplaceId,jobId));
    }
}
