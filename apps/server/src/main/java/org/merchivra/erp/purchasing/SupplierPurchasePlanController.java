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

/** Session Owner/CSRF required; all planning is local until a separately approved PO. */
@RestController
@RequestMapping("/api/v1/purchasing/planning")
public class SupplierPurchasePlanController {
    private final SupplierPurchasePlanService planning;
    private final TenantAccessService access;
    public SupplierPurchasePlanController(SupplierPurchasePlanService planning,
                                          TenantAccessService access) {
        this.planning=planning;
        this.access=access;
    }
    public record RuleRequest(UUID supplierId,UUID productSkuId,int minimumOrderQuantity,
                              int packMultiple,int leadTimeDays,String currency,String unitCost) {}
    @GetMapping("/supplier-rules")
    public List<SupplierPurchasePlanService.Rule> rules(Authentication auth,HttpServletRequest req) {
        return planning.rules(access.currentTenant(auth,req));
    }
    @GetMapping("/supplier-rules/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierPurchasePlanService.Rule>> rulesPage(
        Authentication auth,HttpServletRequest req,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(planning.rulesPage(access.currentTenant(auth,req),limit,cursor));
    }
    @PostMapping("/supplier-rules")
    public SupplierPurchasePlanService.Rule setRule(Authentication auth,HttpServletRequest req,
                                                     @RequestBody RuleRequest body) {
        return planning.setRule(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body.supplierId(),body.productSkuId(),
            body.minimumOrderQuantity(),body.packMultiple(),body.leadTimeDays(),
            body.currency(),body.unitCost());
    }
    @DeleteMapping("/supplier-rules/{supplierId}/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRule(Authentication auth,HttpServletRequest req,
                            @PathVariable UUID supplierId,@PathVariable UUID skuId) {
        planning.removeRule(access.currentTenant(auth,req),
            access.authenticatedUser(auth),supplierId,skuId);
    }
    @GetMapping("/warehouses/{warehouseId}/recommendations")
    public List<SupplierPurchasePlanService.Recommendation> recommendations(
        Authentication auth,HttpServletRequest req,@PathVariable UUID warehouseId) {
        return planning.recommendations(access.currentTenant(auth,req),warehouseId);
    }
    @GetMapping("/warehouses/{warehouseId}/recommendations/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierPurchasePlanService.Recommendation>> recommendationsPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID warehouseId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(planning.recommendationsPage(access.currentTenant(auth,req),warehouseId,limit,cursor));
    }
    @GetMapping("/warehouses/{warehouseId}/plans")
    public List<SupplierPurchasePlanService.Plan> plans(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId) {
        return planning.plans(access.currentTenant(auth,req),warehouseId);
    }
    @GetMapping("/warehouses/{warehouseId}/plans/page")
    public ResponseEntity<LocalKeysetPage.Page<SupplierPurchasePlanService.Plan>> plansPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID warehouseId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(planning.plansPage(access.currentTenant(auth,req),warehouseId,limit,cursor));
    }
    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierPurchasePlanService.Plan propose(Authentication auth,HttpServletRequest req,
        @RequestBody SupplierPurchasePlanService.Request body) {
        return planning.propose(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/plans/{id}/convert")
    public SupplierPurchasePlanService.Plan convert(Authentication auth,HttpServletRequest req,
        @PathVariable UUID id) {
        return planning.change(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"convert");
    }
    @PostMapping("/plans/{id}/cancel")
    public SupplierPurchasePlanService.Plan cancel(Authentication auth,HttpServletRequest req,
        @PathVariable UUID id) {
        return planning.change(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"cancel");
    }
}
