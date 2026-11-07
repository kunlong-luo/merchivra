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

/** Human-controlled local purchase discrepancy and claim APIs; no supplier or finance writes. */
@RestController
@RequestMapping("/api/v1/purchasing/disputes")
public class SupplierDisputeController {
    private final SupplierDisputeService service;
    private final TenantAccessService access;
    public SupplierDisputeController(SupplierDisputeService service,TenantAccessService access) {
        this.service=service;this.access=access;
    }
    public record ActionNote(String note) {}

    @GetMapping("/orders/{orderId}/variances")
    public List<SupplierDisputeService.Variance> variances(Authentication auth,
        HttpServletRequest req,@PathVariable UUID orderId) {
        return service.variances(access.currentTenant(auth,req),orderId);
    }
    @GetMapping("/orders/{orderId}/variances/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierDisputeService.Variance>> variancesPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @RequestParam(defaultValue="ACTIONABLE") String filter,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.variancesPage(access.currentTenant(auth,req),orderId,filter,limit,cursor));
    }
    @PostMapping("/orders/{orderId}/variances")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierDisputeService.Variance report(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId,@RequestBody SupplierDisputeService.Report body) {
        return service.report(access.currentTenant(auth,req),access.authenticatedUser(auth),
            orderId,body);
    }
    @PostMapping("/variances/{id}/{action}")
    public SupplierDisputeService.Variance review(Authentication auth,HttpServletRequest req,
        @PathVariable UUID id,@PathVariable String action) {
        return service.changeVariance(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,action);
    }
    @GetMapping("/orders/{orderId}/claims")
    public List<SupplierDisputeService.Claim> claims(Authentication auth,
        HttpServletRequest req,@PathVariable UUID orderId) {
        return service.claims(access.currentTenant(auth,req),orderId);
    }
    @GetMapping("/orders/{orderId}/claims/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierDisputeService.Claim>> claimsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @RequestParam(defaultValue="ACTIONABLE") String filter,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.claimsPage(access.currentTenant(auth,req),orderId,filter,limit,cursor));
    }
    @GetMapping("/orders/{orderId}/claim-sources/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierDisputeService.ClaimSource>> claimSourcesPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.claimSourcesPage(access.currentTenant(auth,req),orderId,limit,cursor));
    }
    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierDisputeService.Claim createClaim(Authentication auth,HttpServletRequest req,
        @RequestBody SupplierDisputeService.ClaimRequest body) {
        return service.createClaim(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/claims/{id}/{action}")
    public SupplierDisputeService.Claim changeClaim(Authentication auth,HttpServletRequest req,
        @PathVariable UUID id,@PathVariable String action,
        @RequestBody(required=false) ActionNote body) {
        return service.changeClaim(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,action,body==null?null:body.note());
    }
    @GetMapping("/variances/{id}/events")
    public List<SupplierDisputeService.Event> varianceEvents(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id) {
        return service.events(access.currentTenant(auth,req),id,null);
    }
    @GetMapping("/claims/{id}/events")
    public List<SupplierDisputeService.Event> claimEvents(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id) {
        return service.events(access.currentTenant(auth,req),null,id);
    }
    @GetMapping("/variances/{id}/events/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierDisputeService.Event>> varianceEventsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID id,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.eventsPage(access.currentTenant(auth,req),id,"variances",limit,cursor));
    }
    @GetMapping("/claims/{id}/events/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierDisputeService.Event>> claimEventsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID id,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.eventsPage(access.currentTenant(auth,req),id,"claims",limit,cursor));
    }
    @GetMapping("/supplier-performance")
    public List<SupplierDisputeService.Performance> performance(Authentication auth,
        HttpServletRequest req) {
        return service.performance(access.currentTenant(auth,req));
    }
}
