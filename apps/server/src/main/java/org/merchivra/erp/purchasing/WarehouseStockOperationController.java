package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit Owner-only local stock operations; HTTP writes retain Session CSRF. */
@RestController
@RequestMapping("/api/v1/purchasing/warehouse-operations")
public class WarehouseStockOperationController {
    private final TenantAccessService access;
    private final WarehouseStockOperationService service;

    public WarehouseStockOperationController(TenantAccessService access,
                                            WarehouseStockOperationService service) {
        this.access=access;
        this.service=service;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WarehouseStockOperationService.Operation post(Authentication auth,
        HttpServletRequest request,@RequestBody WarehouseStockOperationService.Request body) {
        return service.post(access.currentTenant(auth,request),
            access.authenticatedUser(auth),body);
    }
    @GetMapping
    public List<WarehouseStockOperationService.Operation> recent(Authentication auth,
        HttpServletRequest request,@RequestParam UUID warehouseId) {
        return service.recent(access.currentTenant(auth,request),warehouseId);
    }
}
