package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V50 independent review, receiving acknowledgement and local evidence.
 * Every actor and tenant comes from the current Owner session; writes use CSRF.
 */
@RestController
@RequestMapping("/api/v1/purchasing/lot-reconciliation")
public class LotReconciliationController {
    private final LotReconciliationService service;
    private final TenantAccessService access;
    public LotReconciliationController(LotReconciliationService service,
                                       TenantAccessService access) {
        this.service=service;this.access=access;
    }
    @GetMapping("/warehouses/{warehouseId}/counts")
    public List<LotReconciliationService.CountCase> counts(Authentication auth,
        HttpServletRequest request,@PathVariable UUID warehouseId) {
        return service.counts(access.currentTenant(auth,request),warehouseId);
    }
    @PostMapping("/counts")
    @ResponseStatus(HttpStatus.CREATED)
    public LotReconciliationService.CountCase submitCount(Authentication auth,
        HttpServletRequest request,@RequestBody LotReconciliationService.CountRequest body) {
        return service.submitCount(access.currentTenant(auth,request),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/counts/{id}/decision")
    public LotReconciliationService.CountCase decideCount(Authentication auth,
        HttpServletRequest request,@PathVariable UUID id,
        @RequestBody LotReconciliationService.Decision body) {
        return service.decideCount(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id,body);
    }
    @GetMapping("/warehouses/{warehouseId}/transfers")
    public List<LotReconciliationService.Transfer> transfers(Authentication auth,
        HttpServletRequest request,@PathVariable UUID warehouseId) {
        return service.transfers(access.currentTenant(auth,request),warehouseId);
    }
    @PostMapping("/transfers/{id}/acknowledge")
    public LotReconciliationService.Transfer acknowledge(Authentication auth,
        HttpServletRequest request,@PathVariable UUID id,
        @RequestBody LotReconciliationService.AckRequest body) {
        return service.acknowledge(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id,body);
    }
    @GetMapping("/warehouses/{warehouseId}/dispositions")
    public List<LotReconciliationService.Disposition> dispositions(Authentication auth,
        HttpServletRequest request,@PathVariable UUID warehouseId) {
        return service.dispositions(access.currentTenant(auth,request),warehouseId);
    }
    @GetMapping("/warehouses/{warehouseId}/unplanned-quarantines")
    public List<String> unplannedQuarantines(Authentication auth,
        HttpServletRequest request,@PathVariable UUID warehouseId) {
        return service.unplannedQuarantines(access.currentTenant(auth,request),warehouseId);
    }
    @PostMapping("/dispositions")
    @ResponseStatus(HttpStatus.CREATED)
    public LotReconciliationService.Disposition proposeDisposition(Authentication auth,
        HttpServletRequest request,
        @RequestBody LotReconciliationService.DispositionRequest body) {
        return service.proposeDisposition(access.currentTenant(auth,request),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/dispositions/{id}/decision")
    public LotReconciliationService.Disposition decideDisposition(Authentication auth,
        HttpServletRequest request,@PathVariable UUID id,
        @RequestBody LotReconciliationService.Decision body) {
        return service.decideDisposition(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id,body);
    }
    @PostMapping("/dispositions/{id}/completion")
    public LotReconciliationService.Disposition completeDisposition(Authentication auth,
        HttpServletRequest request,@PathVariable UUID id,
        @RequestBody LotReconciliationService.Completion body) {
        return service.reportCompletion(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id,body);
    }
}
