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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * V194 explicit evidence classification, outside the frozen V64/Orval route
 * contract. Owner Session and same tenant/Marketplace scope as the report.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/capture-verification/{jobId}")
public class ProfitCaptureVerificationControllerV194 {
    private final TenantAccessService access;
    private final ProfitReportJobService reports;
    public ProfitCaptureVerificationControllerV194(
        TenantAccessService access,ProfitReportJobService reports) {
        this.access=access;this.reports=reports;
    }
    @Operation(operationId="verifyFrozenProfitCaptureV194")
    @GetMapping
    public ResponseEntity<ProfitCaptureVerifierV194.Summary> verify(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            reports.captureVerification(access.currentTenant(auth,request),
                marketplaceId,jobId));
    }
}
