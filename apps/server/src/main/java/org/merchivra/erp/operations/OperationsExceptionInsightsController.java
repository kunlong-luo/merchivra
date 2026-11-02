package org.merchivra.erp.operations;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V80 local analysis only. No Amazon outbounds or write endpoints. */
@RestController
@RequestMapping("/api/v1/exception-insights")
public class OperationsExceptionInsightsController {
    private final TenantAccessService access;
    private final OperationsExceptionInsightsService service;
    private final OperationsExceptionHistoryService history;
    private final OperationsExceptionPriorityService priorities;

    public OperationsExceptionInsightsController(TenantAccessService access,
                                                  OperationsExceptionInsightsService service,
                                                  OperationsExceptionHistoryService history,
                                                  OperationsExceptionPriorityService priorities) {
        this.access=access;
        this.service=service;
        this.history=history;
        this.priorities=priorities;
    }
    @GetMapping("/marketplaces/{marketplaceId}/priority-rules")
    public ResponseEntity<List<OperationsExceptionPriorityService.Rule>> rules(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(priorities.rules(access.currentTenant(auth,request),marketplaceId));
    }
    @PostMapping("/marketplaces/{marketplaceId}/priority-rules/{reasonCode}")
    public OperationsExceptionPriorityService.Rule savePriority(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable String reasonCode,
        @RequestBody OperationsExceptionPriorityService.Change input) {
        return priorities.save(access.currentTenant(auth,request),marketplaceId,
            access.authenticatedUser(auth),reasonCode,input);
    }
    @GetMapping("/marketplaces/{marketplaceId}/priority-rules/audit")
    public ResponseEntity<List<OperationsExceptionPriorityService.Audit>> priorityAudit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(priorities.audit(access.currentTenant(auth,request),marketplaceId));
    }

    @GetMapping("/marketplaces/{marketplaceId}/history")
    public ResponseEntity<OperationsExceptionHistoryService.History> history(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(history.history(access.currentTenant(auth,request),marketplaceId));
    }

    @GetMapping("/marketplaces/{marketplaceId}")
    public ResponseEntity<OperationsExceptionInsightsService.Insights> insights(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.insights(access.currentTenant(auth,request),marketplaceId));
    }
}
