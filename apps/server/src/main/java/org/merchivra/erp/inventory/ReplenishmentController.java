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
    public ReplenishmentController(TenantAccessService access,ReplenishmentService service){
        this.access=access;this.service=service;
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
    @GetMapping("/suggestions")
    public List<ReplenishmentService.Advice> advice(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId){
        return service.advice(access.currentTenant(auth,request),marketplaceId);
    }
}
