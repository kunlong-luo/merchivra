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
    @PostMapping
    public ResponseEntity<ProfitReportJobService.Job> create(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody Create body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.create(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),body.requestKey(),body.after(),body.before()));
    }
    @GetMapping
    public ResponseEntity<List<ProfitReportJobService.Job>> recent(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.recent(access.currentTenant(auth,request),marketplaceId));
    }
    @GetMapping("/{jobId}")
    public ResponseEntity<ProfitReportJobService.Report> report(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId,@RequestParam(defaultValue="0") int offset){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,jobId,offset));
    }
    @PostMapping("/{jobId}/step")
    public ResponseEntity<ProfitReportJobService.Job> step(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.step(access.currentTenant(auth,request),marketplaceId,jobId));
    }
    @PostMapping("/{jobId}/resume")
    public ResponseEntity<ProfitReportJobService.Job> resume(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.resume(access.currentTenant(auth,request),marketplaceId,jobId));
    }
    @GetMapping("/{jobId}/audit")
    public ResponseEntity<List<ProfitReportJobService.Audit>> audit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,jobId));
    }
    @GetMapping(value="/{jobId}/export.csv",produces="text/csv")
    public ResponseEntity<String> export(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"merchivra-period-contribution-snapshot.csv\"")
            .contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .body(csv.export(access.currentTenant(auth,request),marketplaceId,jobId));
    }
}
