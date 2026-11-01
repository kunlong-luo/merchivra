package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner/CSRF controlled V49 local lot lifecycle; no SP-API or supplier writes. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-lifecycle")
public class ReleasedLotLifecycleController {
    private final ReleasedLotLifecycleService service;
    private final TenantAccessService access;
    public ReleasedLotLifecycleController(ReleasedLotLifecycleService service,
                                          TenantAccessService access) {
        this.service=service;this.access=access;
    }
    @PostMapping("/positions/{positionId}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleasedLotLifecycleService.Action act(Authentication auth,HttpServletRequest req,
        @PathVariable UUID positionId,@RequestBody ReleasedLotLifecycleService.Request body) {
        return service.act(access.currentTenant(auth,req),
            access.authenticatedUser(auth),positionId,body);
    }
    @GetMapping("/positions/{positionId}/history")
    public List<ReleasedLotLifecycleService.Action> history(Authentication auth,
        HttpServletRequest req,@PathVariable UUID positionId) {
        return service.history(access.currentTenant(auth,req),positionId);
    }
    @GetMapping("/warehouses/{warehouseId}/expiry-alerts")
    public List<ReleasedLotLifecycleService.ExpiryAlert> expiry(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId) {
        return service.expiry(access.currentTenant(auth,req),warehouseId);
    }
}
