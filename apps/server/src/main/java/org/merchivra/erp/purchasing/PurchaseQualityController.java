package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
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
