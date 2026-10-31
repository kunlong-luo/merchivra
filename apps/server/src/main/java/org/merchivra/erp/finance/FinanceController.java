package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Only Owner may browse/review; manual records are never labeled Amazon LIVE. */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final FinanceEventService service;
    private final TenantAccessService access;
    public FinanceController(FinanceEventService service,TenantAccessService access) {
        this.service=service;this.access=access;
    }
    public record ManualRecord(UUID marketplaceId,String eventKey,String category,
        String amount,String currency,OffsetDateTime postedAt,
        String externalOrderId,String sourceType) {}
    public record ReviewBody(UUID marketplaceId,UUID orderId) {}

    @GetMapping("/marketplaces/{marketplaceId}/events")
    public List<FinanceEventService.Event> events(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) OffsetDateTime beforeAt,
        @RequestParam(required=false) UUID beforeId) {
        return service.list(access.currentTenant(auth,request),marketplaceId,limit,beforeAt,beforeId);
    }

    @GetMapping("/marketplaces/{marketplaceId}/buckets")
    public List<FinanceEventService.Bucket> buckets(Authentication auth,HttpServletRequest request,
                                                    @PathVariable UUID marketplaceId) {
        return service.buckets(access.currentTenant(auth,request),marketplaceId);
    }

    @PostMapping("/manual-events")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceEventService.Event manual(Authentication auth,HttpServletRequest request,
                                            @RequestBody ManualRecord body) {
        return service.recordManual(access.currentTenant(auth,request),
            access.authenticatedUser(auth),body.marketplaceId(),
            new FinanceEventService.SourceEvent(body.eventKey(),body.category(),
                body.amount(),body.currency(),body.postedAt(),
                body.externalOrderId(),body.sourceType()));
    }

    @PostMapping("/events/{eventId}/review")
    public FinanceEventService.Review review(Authentication auth,HttpServletRequest request,
        @PathVariable UUID eventId,@RequestBody ReviewBody body) {
        return service.review(access.currentTenant(auth,request),
            access.authenticatedUser(auth),body.marketplaceId(),eventId,body.orderId());
    }
}
