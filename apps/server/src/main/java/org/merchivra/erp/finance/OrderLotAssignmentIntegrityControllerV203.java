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

/** V203 Owner-only read model. No claim-writing or COGS-posting endpoint. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders")
public class OrderLotAssignmentIntegrityControllerV203 {
    private final TenantAccessService access;
    private final OrderLotAssignmentIntegrityServiceV203 service;

    public OrderLotAssignmentIntegrityControllerV203(TenantAccessService access,
        OrderLotAssignmentIntegrityServiceV203 service) {
        this.access=access;
        this.service=service;
    }

    @GetMapping("/{orderId}/sale-lot-integrity")
    public ResponseEntity<OrderLotAssignmentIntegrityServiceV203.Preview> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,request),
                marketplaceId,orderId));
    }
}
