package org.merchivra.erp.operations;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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

    public OperationsExceptionInsightsController(TenantAccessService access,
                                                  OperationsExceptionInsightsService service,
                                                  OperationsExceptionHistoryService history) {
        this.access=access;
        this.service=service;
        this.history=history;
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
