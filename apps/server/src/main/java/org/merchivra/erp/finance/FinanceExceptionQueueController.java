package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-only read of stored local data. No network or Amazon authorization. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/exceptions")
public class FinanceExceptionQueueController {
    private final TenantAccessService access;
    private final FinanceExceptionQueueService queue;
    public FinanceExceptionQueueController(TenantAccessService access,
        FinanceExceptionQueueService queue) {
        this.access=access;this.queue=queue;
    }
    @GetMapping
    public ResponseEntity<FinanceExceptionQueueService.Queue> scan(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@RequestParam(defaultValue="50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            queue.scan(access.currentTenant(authentication,request),marketplaceId,limit));
    }
}
