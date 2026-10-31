package org.merchivra.erp.operations;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** All operations derive tenant & actor from authenticated server Session. CSRF on POST. */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final TenantAccessService access;
    private final OperationsAutomationService service;

    public OperationsController(TenantAccessService access, OperationsAutomationService service) {
        this.access = access;
        this.service = service;
    }
    public record ChangeState(String state) {}

    @GetMapping("/findings")
    public List<OperationsAutomationService.Finding> findings(
        Authentication auth, HttpServletRequest request,
        @RequestParam(defaultValue = "ACTIVE") String filter) {
        return service.findings(access.currentTenant(auth, request), filter);
    }

    @GetMapping("/summary")
    public OperationsAutomationService.Summary summary(Authentication auth, HttpServletRequest request) {
        return service.summary(access.currentTenant(auth, request));
    }

    @PostMapping("/scan")
    public OperationsAutomationService.ScanResult scan(Authentication auth, HttpServletRequest request) {
        UUID tenant = access.currentTenant(auth, request);
        return service.scan(tenant, access.authenticatedUser(auth), "MANUAL");
    }

    @PostMapping("/findings/{findingId}/state")
    public OperationsAutomationService.FindingChange change(
        Authentication auth, HttpServletRequest request,
        @PathVariable UUID findingId, @RequestBody ChangeState body) {
        UUID tenant = access.currentTenant(auth, request);
        return service.changeState(tenant, access.authenticatedUser(auth), findingId,
            body == null ? null : body.state());
    }
}
