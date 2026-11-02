package org.merchivra.erp.fba;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V77: all mutations are local ERP workflow only; Session Owner + CSRF required. */
@RestController
@RequestMapping("/api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/workbench")
public class FbaPurchaseExceptionController {
    private final TenantAccessService access;
    private final FbaPurchaseExceptionService service;
    public FbaPurchaseExceptionController(TenantAccessService access,
                                          FbaPurchaseExceptionService service) {
        this.access=access;
        this.service=service;
    }
    @GetMapping
    public ResponseEntity<FbaPurchaseExceptionService.Board> board(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.board(access.currentTenant(auth,request),marketplaceId));
    }
    @GetMapping("/workload")
    public ResponseEntity<FbaPurchaseExceptionService.Workload> workload(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.workload(access.currentTenant(auth,request),marketplaceId));
    }
    @PostMapping("/bulk-assign")
    public FbaPurchaseExceptionService.BulkAssignReceipt bulkAssign(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.BulkAssign body) {
        return service.bulkAssign(access.currentTenant(auth,request),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/metrics")
    public ResponseEntity<FbaPurchaseExceptionService.Metrics> metrics(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.metrics(access.currentTenant(auth,request),marketplaceId));
    }
    @PostMapping("/cases/{lineId}/actions")
    public FbaPurchaseExceptionService.WorkItem act(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID lineId,@RequestBody FbaPurchaseExceptionService.Action body) {
        return service.act(access.currentTenant(auth,request),marketplaceId,
            access.authenticatedUser(auth),lineId,body);
    }
    @PostMapping("/bulk-acknowledge")
    public FbaPurchaseExceptionService.BulkReceipt bulk(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.Bulk body) {
        return service.bulkAcknowledge(access.currentTenant(auth,request),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/cases/{lineId}/history")
    public ResponseEntity<List<FbaPurchaseExceptionService.History>> history(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID lineId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(auth,request),marketplaceId,lineId));
    }
    @PostMapping("/policy")
    public FbaPurchaseExceptionService.Policy savePolicy(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.SavePolicy body) {
        return service.savePolicy(access.currentTenant(auth,request),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/policy/history")
    public ResponseEntity<List<FbaPurchaseExceptionService.PolicyHistory>> policyHistory(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.policyHistory(access.currentTenant(auth,request),marketplaceId));
    }
}
