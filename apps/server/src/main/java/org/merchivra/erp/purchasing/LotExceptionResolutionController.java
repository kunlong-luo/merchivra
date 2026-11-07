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

/** V51 exceptions are explicit Owner + CSRF actions, never Amazon writes. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-exceptions")
public class LotExceptionResolutionController {
    private final LotExceptionResolutionService service;
    private final TenantAccessService access;
    public LotExceptionResolutionController(LotExceptionResolutionService service,
                                            TenantAccessService access){
        this.service=service;this.access=access;
    }
    @GetMapping("/warehouses/{warehouseId}")
    public List<LotExceptionResolutionService.Resolution> list(Authentication auth,
        HttpServletRequest request,@PathVariable UUID warehouseId){
        return service.list(access.currentTenant(auth,request),warehouseId);
    }
    @GetMapping("/warehouses/{warehouseId}/page")
    public ResponseEntity<LocalKeysetPage.Page<LotExceptionResolutionService.Resolution>> listPage(
        Authentication auth,HttpServletRequest request,@PathVariable UUID warehouseId,
        @RequestParam(defaultValue="PENDING") String filter,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.listPage(access.currentTenant(auth,request),warehouseId,filter,limit,cursor));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LotExceptionResolutionService.Resolution propose(Authentication auth,
        HttpServletRequest request,
        @RequestBody LotExceptionResolutionService.Proposal body){
        return service.propose(access.currentTenant(auth,request),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/{id}/decision")
    public LotExceptionResolutionService.Resolution decide(Authentication auth,
        HttpServletRequest request,@PathVariable UUID id,
        @RequestBody LotExceptionResolutionService.Decision body){
        return service.decide(access.currentTenant(auth,request),
            access.authenticatedUser(auth),id,body);
    }
}
