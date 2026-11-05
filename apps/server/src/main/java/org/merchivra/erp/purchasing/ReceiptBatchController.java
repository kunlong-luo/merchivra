package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Only Owner session identity determines tenant and actor; writes require CSRF. */
@RestController
@RequestMapping("/api/v1/purchasing/receipt-batches")
public class ReceiptBatchController {
    private final ReceiptBatchService service;
    private final TenantAccessService access;
    public ReceiptBatchController(ReceiptBatchService service,TenantAccessService access){
        this.service=service;this.access=access;
    }
    @GetMapping("/orders/{orderId}")
    public List<ReceiptBatchService.Batch> forOrder(Authentication auth,HttpServletRequest req,
        @PathVariable UUID orderId) {
        return service.forOrder(access.currentTenant(auth,req),orderId);
    }
    @GetMapping("/orders/{orderId}/page")
    public ResponseEntity<LocalKeysetPage.Page<ReceiptBatchService.Batch>> forOrderPage(
        Authentication auth,HttpServletRequest req,@PathVariable UUID orderId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.forOrderPage(access.currentTenant(auth,req),orderId,limit,cursor));
    }
    @GetMapping("/warehouses/{warehouseId}")
    public List<ReceiptBatchService.Batch> forWarehouse(Authentication auth,HttpServletRequest req,
        @PathVariable UUID warehouseId) {
        return service.forWarehouse(access.currentTenant(auth,req),warehouseId);
    }
    @PostMapping("/{receiptId}/relabel")
    public ReceiptBatchService.Batch relabel(Authentication auth,HttpServletRequest req,
        @PathVariable UUID receiptId,@RequestBody ReceiptBatchService.LotChange body) {
        return service.relabel(access.currentTenant(auth,req),access.authenticatedUser(auth),
            receiptId,body);
    }
    @PostMapping("/{receiptId}/release")
    public ReceiptBatchService.Batch release(Authentication auth,HttpServletRequest req,
        @PathVariable UUID receiptId) {
        return service.release(access.currentTenant(auth,req),access.authenticatedUser(auth),
            receiptId);
    }
    @GetMapping("/{receiptId}/events")
    public List<ReceiptBatchService.Event> events(Authentication auth,HttpServletRequest req,
        @PathVariable UUID receiptId) {
        return service.events(access.currentTenant(auth,req),receiptId);
    }
}
