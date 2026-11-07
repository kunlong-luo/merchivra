package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner + Session + CSRF; local QC does not mutate Amazon listings or FBA. */
@RestController
@RequestMapping("/api/v1/purchasing/quality")
public class PurchaseQualityController {
    private final TenantAccessService access;
    private final PurchaseQualityService quality;
    public PurchaseQualityController(TenantAccessService access,PurchaseQualityService quality) {
        this.access=access;this.quality=quality;
    }
    public record StateBody(String state) {}
    @GetMapping("/orders/{orderId}/receipts")
    public List<PurchaseQualityService.Receipt> receipts(Authentication auth,
        HttpServletRequest req,@PathVariable UUID orderId) {
        return quality.receipts(access.currentTenant(auth,req),orderId);
    }
    @GetMapping("/orders/{orderId}/receipts/page")
    public ResponseEntity<LocalKeysetPage.Page<PurchaseQualityService.Receipt>> receiptsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(quality.receiptsPage(access.currentTenant(auth,req),orderId,limit,cursor));
    }
    @PostMapping("/receipts/{receiptId}/inspect")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseQualityService.Inspection inspect(Authentication auth,
        HttpServletRequest req,@PathVariable UUID receiptId,
        @RequestBody PurchaseQualityService.InspectRequest body) {
        return quality.inspect(access.currentTenant(auth,req),access.authenticatedUser(auth),
            receiptId,body);
    }
    @GetMapping("/inspections/{inspectionId}/returns")
    public List<PurchaseQualityService.SupplierReturn> returns(Authentication auth,
        HttpServletRequest req,@PathVariable UUID inspectionId) {
        return quality.returns(access.currentTenant(auth,req),inspectionId);
    }
    @GetMapping("/inspections/{inspectionId}/returns/page")
    public ResponseEntity<LocalKeysetPage.Page<PurchaseQualityService.SupplierReturn>> returnsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID inspectionId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(quality.returnsPage(access.currentTenant(auth,req),inspectionId,limit,cursor));
    }
    @GetMapping("/inspections/{inspectionId}/returns/summary")
    public ResponseEntity<PurchaseQualityService.ReturnSummary> returnsSummary(
        Authentication auth,HttpServletRequest req,@PathVariable UUID inspectionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(quality.returnsSummary(access.currentTenant(auth,req),inspectionId));
    }
    @PostMapping("/inspections/{inspectionId}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseQualityService.SupplierReturn createReturn(Authentication auth,
        HttpServletRequest req,@PathVariable UUID inspectionId,
        @RequestBody PurchaseQualityService.ReturnRequest body) {
        return quality.createReturn(access.currentTenant(auth,req),
            access.authenticatedUser(auth),inspectionId,body);
    }
    @PostMapping("/returns/{id}/dispatch")
    public PurchaseQualityService.SupplierReturn dispatch(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id) {
        return quality.changeReturn(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"dispatch");
    }
    @PostMapping("/returns/{id}/cancel")
    public PurchaseQualityService.SupplierReturn cancel(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id) {
        return quality.changeReturn(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"cancel");
    }
    @GetMapping("/findings")
    public List<PurchaseQualityService.Finding> findings(Authentication auth,
        HttpServletRequest req,@RequestParam(defaultValue="ACTIVE") String filter) {
        return quality.findings(access.currentTenant(auth,req),filter);
    }
    @GetMapping("/findings/page")
    public ResponseEntity<LocalKeysetPage.Page<PurchaseQualityService.Finding>> findingsPage(
        Authentication auth,HttpServletRequest req,
        @RequestParam(defaultValue="ACTIVE") String filter,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(quality.findingsPage(access.currentTenant(auth,req),filter,limit,cursor));
    }
    @PostMapping("/scan")
    public PurchaseQualityService.ScanResult scan(Authentication auth,HttpServletRequest req) {
        return quality.scan(access.currentTenant(auth,req),
            access.authenticatedUser(auth),"MANUAL");
    }
    @PostMapping("/findings/{id}/state")
    public PurchaseQualityService.FindingChange changeFinding(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id,@RequestBody StateBody body) {
        return quality.changeFinding(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,body==null?null:body.state());
    }
}
