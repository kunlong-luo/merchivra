package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** V175-B owner-only key control, never third-party operator identity. */
@RestController
@RequestMapping("/api/v1/finance/wms-signing-sources/{sourceId}/key-possession")
public class WmsKeyPossessionController {
    private final TenantAccessService access;
    private final WmsKeyPossessionService service;
    public WmsKeyPossessionController(TenantAccessService access,
        WmsKeyPossessionService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="listWmsKeyPossessionChallenges")
    @GetMapping
    public ResponseEntity<List<WmsKeyPossessionService.Challenge>> list(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.list(access.currentTenant(auth,request),sourceId));
    }
    @Operation(operationId="issueWmsKeyPossessionChallenge")
    @PostMapping("/challenges")
    public ResponseEntity<WmsKeyPossessionService.Challenge> issue(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.issue(access.currentTenant(auth,request),sourceId,
                access.authenticatedUser(auth)));
    }
    @Operation(operationId="verifyWmsKeyPossessionChallenge")
    @PostMapping("/challenges/{challengeId}/verify")
    public ResponseEntity<WmsKeyPossessionService.Challenge> verify(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID sourceId,@PathVariable UUID challengeId,
        @RequestBody WmsKeyPossessionService.ProofInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.verify(access.currentTenant(auth,request),sourceId,challengeId,
                access.authenticatedUser(auth),body));
    }
}
