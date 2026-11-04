package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * Owner-only V89 business overview. Staff read restrictions remain unchanged:
 * source counts / V64 finance report references are not a new OPERATOR_READ grant.
 */
@RestController
@RequestMapping("/api/v1/business-overview")
public class BusinessOverviewController {
    private final TenantAccessService access;
    private final BusinessOverviewService service;
    public BusinessOverviewController(TenantAccessService access,
                                      BusinessOverviewService service) {
        this.access=access;
        this.service=service;
    }
    @Operation(operationId="getBusinessOverview")
    @GetMapping
    public ResponseEntity<BusinessOverviewService.Overview> overview(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.read(access.currentTenant(auth,request)));
    }

    /** Source QUALITY export, not a financial statement or a frozen V64 report. */
    @Operation(operationId="exportBusinessOverviewQualityCsv")
    @GetMapping(value="/export.csv",produces="text/csv")
    public ResponseEntity<String> export(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request) {
        var snapshot=service.read(access.currentTenant(auth,request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"merchivra-business-source-quality.csv\"")
            .contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .body(BusinessOverviewService.csv(snapshot));
    }
}
