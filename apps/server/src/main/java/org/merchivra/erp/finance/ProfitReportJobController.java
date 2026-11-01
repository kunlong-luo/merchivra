package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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

/** V64 persistent, read-only source contribution extracts; Owner Session only. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/profit-reports")
public class ProfitReportJobController {
    private final TenantAccessService access;
    private final ProfitReportJobService service;
    private final ProfitReportCsv csv;
    public ProfitReportJobController(TenantAccessService access,
        ProfitReportJobService service,ProfitReportCsv csv){
        this.access=access;this.service=service;this.csv=csv;
    }
    public record Create(UUID requestKey,LocalDate after,LocalDate before) {}
    @Operation(operationId="createProfitReport")
    @PostMapping
    public ResponseEntity<ProfitReportJobService.Job> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody Create body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.create(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),body.requestKey(),body.after(),body.before()));
    }
    @Operation(operationId="listProfitReports")
    @GetMapping
    public ResponseEntity<List<ProfitReportJobService.Job>> recent(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.recent(access.currentTenant(auth,request),marketplaceId));
    }
    @Operation(operationId="getProfitReport")
    @GetMapping("/{jobId}")
    public ResponseEntity<ProfitReportJobService.Report> report(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId,@RequestParam(defaultValue="0") int offset){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,jobId,offset));
    }
    @Operation(operationId="advanceProfitReport")
    @PostMapping("/{jobId}/step")
    public ResponseEntity<ProfitReportJobService.Job> step(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.step(access.currentTenant(auth,request),marketplaceId,jobId));
    }
    @Operation(operationId="resumeProfitReport")
    @PostMapping("/{jobId}/resume")
    public ResponseEntity<ProfitReportJobService.Job> resume(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.resume(access.currentTenant(auth,request),marketplaceId,jobId));
    }
    @Operation(operationId="getProfitReportAudit")
    @GetMapping("/{jobId}/audit")
    public ResponseEntity<List<ProfitReportJobService.Audit>> audit(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,jobId));
    }
    @Operation(operationId="exportProfitReportCsv")
    @GetMapping(value="/{jobId}/export.csv",produces="text/csv")
    public ResponseEntity<String> export(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"merchivra-period-contribution-snapshot.csv\"")
            .contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .body(csv.export(access.currentTenant(auth,request),marketplaceId,jobId));
    }
}
