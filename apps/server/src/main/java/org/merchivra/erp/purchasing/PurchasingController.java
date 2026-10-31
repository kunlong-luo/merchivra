package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** All purchase mutations are Owner scoped and protected by Spring Session CSRF. */
@RestController
@RequestMapping("/api/v1/purchasing")
public class PurchasingController {
    private final PurchasingService purchasing;
    private final WarehouseInventoryService warehouses;
    private final TenantAccessService access;
    public PurchasingController(PurchasingService purchasing,WarehouseInventoryService warehouses,
                                TenantAccessService access){
        this.purchasing=purchasing;this.warehouses=warehouses;this.access=access;
    }
    public record NewSupplier(String supplierCode,String displayName) {}
    public record NewOrder(UUID supplierId,String currency,String notes,
                           List<PurchasingService.RequestedLine> lines) {}
    public record Receive(UUID lineId,int quantity,UUID warehouseId,UUID asnId) {}
    public record NewWarehouse(String warehouseCode,String displayName) {}

    @GetMapping("/warehouses")
    public List<WarehouseInventoryService.Warehouse> warehouses(Authentication auth,
                                                                HttpServletRequest req) {
        return warehouses.warehouses(access.currentTenant(auth,req));
    }
    @PostMapping("/warehouses")
    @ResponseStatus(HttpStatus.CREATED)
    public WarehouseInventoryService.Warehouse createWarehouse(Authentication auth,
        HttpServletRequest req,@RequestBody NewWarehouse body) {
        return warehouses.create(access.currentTenant(auth,req),access.authenticatedUser(auth),
            body.warehouseCode(),body.displayName());
    }
    @GetMapping("/warehouse-stock")
    public List<WarehouseInventoryService.Stock> stock(Authentication auth,
        HttpServletRequest req,@RequestParam UUID warehouseId) {
        return warehouses.stock(access.currentTenant(auth,req),warehouseId);
    }
    @GetMapping("/warehouse-ledger")
    public List<WarehouseInventoryService.Movement> ledger(Authentication auth,
        HttpServletRequest req,@RequestParam UUID warehouseId) {
        return warehouses.ledger(access.currentTenant(auth,req),warehouseId);
    }
    @GetMapping("/pending-receipts")
    public List<WarehouseInventoryService.PendingReceipt> pendingReceipts(
        Authentication auth,HttpServletRequest req) {
        return warehouses.pendingReceipts(access.currentTenant(auth,req));
    }
    @GetMapping("/suppliers")
    public List<PurchasingService.Supplier> suppliers(Authentication auth,HttpServletRequest req){
        return purchasing.suppliers(access.currentTenant(auth,req));
    }
    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchasingService.Supplier createSupplier(Authentication auth,HttpServletRequest req,
                                                      @RequestBody NewSupplier body){
        return purchasing.createSupplier(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body.supplierCode(),body.displayName());
    }
    @GetMapping("/orders")
    public List<PurchasingService.Order> orders(Authentication auth,HttpServletRequest req){
        return purchasing.orders(access.currentTenant(auth,req));
    }
    @GetMapping("/orders/{id}")
    public PurchasingService.Detail detail(Authentication auth,HttpServletRequest req,
                                            @PathVariable UUID id){
        return purchasing.detail(access.currentTenant(auth,req),id);
    }
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchasingService.Detail create(Authentication auth,HttpServletRequest req,
                                            @RequestBody NewOrder body){
        return purchasing.create(access.currentTenant(auth,req),access.authenticatedUser(auth),
            body.supplierId(),body.currency(),body.notes(),body.lines());
    }
    @PostMapping("/orders/{id}/submit")
    public PurchasingService.Detail submit(Authentication auth,HttpServletRequest req,
                                            @PathVariable UUID id){
        return purchasing.transition(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"submit");
    }
    @PostMapping("/orders/{id}/approve")
    public PurchasingService.Detail approve(Authentication auth,HttpServletRequest req,
                                             @PathVariable UUID id){
        return purchasing.transition(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"approve");
    }
    @PostMapping("/orders/{id}/cancel")
    public PurchasingService.Detail cancel(Authentication auth,HttpServletRequest req,
                                            @PathVariable UUID id){
        return purchasing.transition(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,"cancel");
    }
    @PostMapping("/orders/{id}/receive")
    public PurchasingService.Detail receive(Authentication auth,HttpServletRequest req,
                                             @PathVariable UUID id,@RequestBody Receive body){
        return purchasing.receive(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,body.lineId(),body.quantity(),
            body.warehouseId(),body.asnId());
    }
}
