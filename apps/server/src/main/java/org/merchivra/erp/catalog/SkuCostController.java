package org.merchivra.erp.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog/skus/{skuId}/costs")
public class SkuCostController {
    private final SkuCostService service;
    private final TenantAccessService access;
    public SkuCostController(SkuCostService service,TenantAccessService access) {
        this.service=service;this.access=access;
    }
    public record NewCost(String unitCost,String currency,LocalDate effectiveFrom) {}
    @GetMapping
    public List<SkuCostService.Cost> history(Authentication auth,HttpServletRequest request,
                                             @PathVariable UUID skuId) {
        return service.history(access.currentTenant(auth,request),skuId);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkuCostService.Cost add(Authentication auth,HttpServletRequest request,
                                   @PathVariable UUID skuId,@RequestBody NewCost body) {
        return service.add(access.currentTenant(auth,request),
            access.authenticatedUser(auth),skuId,body.unitCost(),body.currency(),
            body.effectiveFrom());
    }
}
