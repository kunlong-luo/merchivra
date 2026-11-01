package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V51 metadata only: no binary upload, no external supplier verification. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-disposition-proofs")
public class LotDispositionProofController {
    private final LotDispositionProofService service;
    private final TenantAccessService access;
    public LotDispositionProofController(LotDispositionProofService service,
                                         TenantAccessService access) {
        this.service=service;this.access=access;
    }
    @GetMapping("/warehouses/{warehouseId}")
    public List<LotDispositionProofService.Proof> list(Authentication auth,
        HttpServletRequest req,@PathVariable UUID warehouseId){
        return service.list(access.currentTenant(auth,req),warehouseId);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LotDispositionProofService.Proof submit(Authentication auth,
        HttpServletRequest req,@RequestBody LotDispositionProofService.Submit body){
        return service.submit(access.currentTenant(auth,req),
            access.authenticatedUser(auth),body);
    }
    @PostMapping("/{id}/decision")
    public LotDispositionProofService.Proof decide(Authentication auth,
        HttpServletRequest req,@PathVariable UUID id,
        @RequestBody LotDispositionProofService.Decision body){
        return service.decide(access.currentTenant(auth,req),
            access.authenticatedUser(auth),id,body);
    }
}
