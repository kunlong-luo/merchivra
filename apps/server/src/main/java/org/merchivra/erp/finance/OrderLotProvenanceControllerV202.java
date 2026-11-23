package org.merchivra.erp.finance;

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

/** V202 only: same Owner-tenant guard as the existing order COGS preview. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders")
public class OrderLotProvenanceControllerV202 {
    private final TenantAccessService access;
    private final OrderLotProvenanceServiceV202 lots;
    public OrderLotProvenanceControllerV202(TenantAccessService access,
        OrderLotProvenanceServiceV202 lots) {
        this.access=access;
        this.lots=lots;
    }
    @GetMapping("/{orderId}/lot-provenance")
    public ResponseEntity<OrderLotProvenanceServiceV202.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(lots.preview(access.currentTenant(auth,request),
                marketplaceId,orderId));
    }
}
