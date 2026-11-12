package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V138 private Owner-only governance; never a byte deletion endpoint. */
@RestController
@RequestMapping("/api/v1/purchasing/lot-proof-governance")
public class LotProofGovernanceController {
    private final LotProofGovernanceService service;
    private final TenantAccessService access;
    public LotProofGovernanceController(LotProofGovernanceService service,
                                       TenantAccessService access){
        this.service=service;this.access=access;
    }
    @GetMapping("/capacity")
    public ResponseEntity<LotProofGovernanceService.Capacity> capacity(
        Authentication auth,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.capacity(access.currentTenant(auth,request)));
    }
    @GetMapping("/{proofId}")
    public ResponseEntity<LotProofGovernanceService.Governance> status(
        Authentication auth,HttpServletRequest request,@PathVariable UUID proofId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.status(access.currentTenant(auth,request),proofId));
    }
    @PostMapping("/{proofId}/commands")
    public ResponseEntity<LotProofGovernanceService.Governance> command(
        Authentication auth,HttpServletRequest request,@PathVariable UUID proofId,
        @RequestBody LotProofGovernanceService.Command body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.command(access.currentTenant(auth,request),
                access.authenticatedUser(auth),proofId,body));
    }
}
