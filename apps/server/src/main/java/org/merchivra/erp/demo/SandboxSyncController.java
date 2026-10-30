package org.merchivra.erp.demo;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/demo/sandbox")
@ConditionalOnProperty(prefix="merchivra.demo", name="enabled", havingValue="true")
public class SandboxSyncController {
    private final SandboxSyncService service;
    private final TenantAccessService access;
    public SandboxSyncController(SandboxSyncService service, TenantAccessService access) {
        this.service = service; this.access = access;
    }

    public record Request(boolean simulateFailure) {}

    @PostMapping("/{marketplaceId}/replay")
    @ResponseStatus(HttpStatus.CREATED)
    public SandboxSyncService.Result replay(Authentication auth, HttpServletRequest request,
        @PathVariable UUID marketplaceId, @RequestHeader("Idempotency-Key") String key,
        @RequestBody(required=false) Request body) {
        UUID tenant = access.currentTenant(auth, request);
        UUID actor = access.authenticatedUser(auth);
        return service.replay(tenant, actor, marketplaceId, key,
            body != null && body.simulateFailure());
    }

    @GetMapping("/{marketplaceId}/runs")
    public List<SandboxSyncService.Result> recent(Authentication auth, HttpServletRequest request,
        @PathVariable UUID marketplaceId) {
        return service.recent(access.currentTenant(auth, request), marketplaceId);
    }
}
