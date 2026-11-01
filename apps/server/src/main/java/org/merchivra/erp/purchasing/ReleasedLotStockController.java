package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit local stock mutations; no automatic FBA/FBM or supplier actions. */
@RestController
@RequestMapping("/api/v1/purchasing/released-lots")
public class ReleasedLotStockController {
    private final ReleasedLotStockService service;
    private final TenantAccessService access;
    public ReleasedLotStockController(ReleasedLotStockService service,TenantAccessService access){
        this.service=service;this.access=access;
    }
    @GetMapping("/warehouses/{warehouseId}/positions")
    public List<ReleasedLotStockService.Position> positions(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId){
        return service.positions(access.currentTenant(auth,req),warehouseId);
    }
    @PostMapping("/putaway")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleasedLotStockService.Position putaway(Authentication auth,HttpServletRequest req,
        @RequestBody ReleasedLotStockService.PutawayRequest body){
        return service.putaway(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/warehouses/{warehouseId}/skus/{skuId}/pick-preview")
    public List<ReleasedLotStockService.Position> preview(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId,
        @PathVariable UUID skuId,@RequestParam(defaultValue="FIFO") String policy){
        return service.preview(access.currentTenant(auth,req),warehouseId,skuId,policy);
    }
    @PostMapping("/picks")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleasedLotStockService.Pick pick(Authentication auth,HttpServletRequest req,
        @RequestBody ReleasedLotStockService.PickRequest body){
        return service.pick(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body);
    }
}
