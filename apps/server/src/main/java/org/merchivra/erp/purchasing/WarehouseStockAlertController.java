package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner/session scoped local warehouse advisory APIs; POST/DELETE require CSRF. */
@RestController
@RequestMapping("/api/v1/purchasing/warehouse-alerts")
public class WarehouseStockAlertController {
    private final TenantAccessService access;
    private final WarehouseStockAlertService service;
    public WarehouseStockAlertController(TenantAccessService access,
                                         WarehouseStockAlertService service) {
        this.access=access;
        this.service=service;
    }
    public record PolicyBody(UUID productSkuId,int reorderPoint,int targetStock) {}
    public record StateBody(String state) {}

    @GetMapping("/{warehouseId}/advice")
    public List<WarehouseStockAlertService.Advice> advice(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId) {
        return service.advice(access.currentTenant(auth,req),warehouseId);
    }
    @PostMapping("/{warehouseId}/policies")
    public WarehouseStockAlertService.Policy setPolicy(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId,@RequestBody PolicyBody body) {
        return service.setPolicy(access.currentTenant(auth,req),access.authenticatedUser(auth),
            warehouseId,body.productSkuId(),body.reorderPoint(),body.targetStock());
    }
    @DeleteMapping("/{warehouseId}/policies/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth,HttpServletRequest req,
        @PathVariable UUID warehouseId,@PathVariable UUID skuId) {
        service.removePolicy(access.currentTenant(auth,req),
            access.authenticatedUser(auth),warehouseId,skuId);
    }
    @GetMapping("/{warehouseId}/findings")
    public List<WarehouseStockAlertService.Finding> findings(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId,
        @RequestParam(defaultValue="ACTIVE") String filter) {
        return service.findings(access.currentTenant(auth,req),warehouseId,filter);
    }
    @PostMapping("/scan")
    public WarehouseStockAlertService.ScanResult scan(Authentication auth,HttpServletRequest req) {
        return service.scan(access.currentTenant(auth,req),
            access.authenticatedUser(auth),"MANUAL");
    }
    @PostMapping("/findings/{findingId}/state")
    public WarehouseStockAlertService.FindingChange change(Authentication auth,
        HttpServletRequest req,@PathVariable UUID findingId,@RequestBody StateBody body) {
        return service.changeState(access.currentTenant(auth,req),
            access.authenticatedUser(auth),findingId,body==null?null:body.state());
    }
}
