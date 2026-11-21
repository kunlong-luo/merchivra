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

/** Owner/session scoped local supplier ASN and shipment-claim review API. */
@RestController
@RequestMapping("/api/v1/purchasing/supplier-asns")
public class SupplierAsnController {
    private final SupplierAsnService asns;
    private final TenantAccessService access;
    public SupplierAsnController(SupplierAsnService asns,TenantAccessService access) {
        this.asns=asns;this.access=access;
    }
    public record StateBody(String state) {}

    @GetMapping("/orders/{orderId}")
    public List<SupplierAsnService.Asn> list(Authentication auth,HttpServletRequest req,
                                              @PathVariable UUID orderId) {
        return asns.list(access.currentTenant(auth,req),orderId);
    }
    @GetMapping("/orders/{orderId}/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierAsnService.Asn>> listPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(asns.listPage(access.currentTenant(auth,req),orderId,limit,cursor));
    }
    @GetMapping("/orders/{orderId}/{asnId}/events/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierAsnService.Event>> eventsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @PathVariable UUID asnId,@RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(asns.eventsPage(access.currentTenant(auth,req),
                orderId,asnId,limit,cursor));
    }
    @GetMapping("/orders/{orderId}/{asnId}/events")
    public List<SupplierAsnService.Event> events(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId,@PathVariable UUID asnId) {
        return asns.events(access.currentTenant(auth,req),orderId,asnId);
    }
    @PostMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierAsnService.Asn create(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId,@RequestBody SupplierAsnService.Create body) {
        return asns.create(access.currentTenant(auth,req),access.authenticatedUser(auth),
            orderId,body);
    }
    @PostMapping("/orders/{orderId}/{asnId}/ship")
    public SupplierAsnService.Asn ship(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId,@PathVariable UUID asnId) {
        return asns.transition(access.currentTenant(auth,req),
            access.authenticatedUser(auth),orderId,asnId,"ship");
    }
    @PostMapping("/orders/{orderId}/{asnId}/cancel")
    public SupplierAsnService.Asn cancel(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId,@PathVariable UUID asnId) {
        return asns.transition(access.currentTenant(auth,req),
            access.authenticatedUser(auth),orderId,asnId,"cancel");
    }
    @PostMapping("/orders/{orderId}/{asnId}/eta")
    public SupplierAsnService.Asn updateEta(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId,@PathVariable UUID asnId,
        @RequestBody SupplierAsnService.ChangeEta body) {
        return asns.updateEta(access.currentTenant(auth,req),
            access.authenticatedUser(auth),orderId,asnId,body);
    }
    @GetMapping("/findings")
    public List<SupplierAsnService.Finding> findings(Authentication auth,HttpServletRequest req,
        @RequestParam(defaultValue="ACTIVE") String filter) {
        return asns.findings(access.currentTenant(auth,req),filter);
    }
    @PostMapping("/scan")
    public SupplierAsnService.ScanResult scan(Authentication auth,HttpServletRequest req) {
        return asns.scan(access.currentTenant(auth,req),
            access.authenticatedUser(auth),"MANUAL");
    }
    @PostMapping("/findings/{findingId}/state")
    public SupplierAsnService.FindingChange change(Authentication auth,HttpServletRequest req,
        @PathVariable UUID findingId,@RequestBody StateBody body) {
        return asns.changeState(access.currentTenant(auth,req),
            access.authenticatedUser(auth),findingId,body==null?null:body.state());
    }
}
