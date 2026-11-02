package org.merchivra.erp.inventory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-only replenishment advice and config; no purchase/Amazon write calls. */
@RestController
@RequestMapping("/api/v1/replenishment/marketplaces/{marketplaceId}")
public class ReplenishmentController {
    private final TenantAccessService access;
    private final ReplenishmentService service;
    private final ReplenishmentSupplierQuoteService quotes;
    private final ReplenishmentDemandService demand;
    private final ReplenishmentPriorityService priority;
    public ReplenishmentController(TenantAccessService access,ReplenishmentService service,
                                   ReplenishmentDemandService demand,
                                   ReplenishmentSupplierQuoteService quotes,
                                   ReplenishmentPriorityService priority){
        this.access=access;this.service=service;this.demand=demand;this.quotes=quotes;this.priority=priority;
    }
    public record SetPolicy(String sellerSku,int reorderPoint,int targetStock,int leadTimeDays) {}
    @GetMapping("/policies")
    public List<ReplenishmentService.Policy> policies(Authentication auth,
        HttpServletRequest request,@PathVariable UUID marketplaceId){
        return service.policies(access.currentTenant(auth,request),marketplaceId);
    }
    @PostMapping("/policies")
    public ReplenishmentService.Policy set(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@RequestBody SetPolicy body){
        return service.set(access.currentTenant(auth,request),
            access.authenticatedUser(auth),marketplaceId,body.sellerSku(),
            body.reorderPoint(),body.targetStock(),body.leadTimeDays());
    }
    @DeleteMapping("/policies")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@RequestParam String sellerSku){
        service.remove(access.currentTenant(auth,request),
            access.authenticatedUser(auth),marketplaceId,sellerSku);
    }
    /** Explicit observed-quantity read: never modifies PO, purchasing or Amazon. */
    @GetMapping("/demand-signals")
    public List<ReplenishmentDemandService.Signal> demandSignals(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId){
        return demand.signals(access.currentTenant(auth,request),marketplaceId);
    }

    /** V71: Supplier SKU terms + validated OBSERVED stock, never creates a PO. */
    @GetMapping("/supplier-quotes")
    public List<ReplenishmentSupplierQuoteService.Quote> supplierQuotes(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return quotes.quotes(access.currentTenant(auth,request),marketplaceId);
    }

    /** V74: evidence-qualified advisory only. Never deducts local POs from FBA. */
    @GetMapping("/priorities")
    public List<ReplenishmentPriorityService.Priority> priorities(Authentication auth,
        HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return priority.priorities(access.currentTenant(auth,request),marketplaceId);
    }

    @GetMapping("/suggestions")
    public List<ReplenishmentService.Advice> advice(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId){
        return service.advice(access.currentTenant(auth,request),marketplaceId);
    }
}
