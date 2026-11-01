package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** All V52 supplier-related statements are manual LOCAL reports, not SP-API writes. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-supplier-returns")
public class LotSupplierReturnTraceController {
    private final LotSupplierReturnTraceService service;
    private final TenantAccessService access;
    public LotSupplierReturnTraceController(LotSupplierReturnTraceService service,
                                            TenantAccessService access){
        this.service=service;this.access=access;
    }
    @GetMapping("/warehouses/{warehouseId}")
    public List<LotSupplierReturnTraceService.Trace> list(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId){
        return service.list(access.currentTenant(auth,req),warehouseId);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LotSupplierReturnTraceService.Trace dispatch(Authentication auth,
        HttpServletRequest req,@RequestBody LotSupplierReturnTraceService.Dispatch body){
        return service.reportDispatch(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/{id}/receipt")
    public LotSupplierReturnTraceService.Trace receipt(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id,
        @RequestBody LotSupplierReturnTraceService.Receipt body){
        return service.reportReceipt(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,body);
    }
}
