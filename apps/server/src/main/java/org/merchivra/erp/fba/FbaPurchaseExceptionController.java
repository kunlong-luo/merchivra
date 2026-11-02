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
    private final FbaExceptionPermissionService permissions;
    private UUID permitted(Authentication auth,HttpServletRequest request,
                           UUID marketplace,FbaExceptionPermissionService.Capability capability) {
        UUID tenant=access.currentTenant(auth,request);
        permissions.require(tenant,marketplace,access.authenticatedUser(auth),capability);
        return tenant;
    }
    public FbaPurchaseExceptionController(TenantAccessService access,
                                          FbaPurchaseExceptionService service,
                                          FbaExceptionPermissionService permissions) {
        this.access=access;
        this.service=service;
        this.permissions=permissions;
    }
    @GetMapping
    public ResponseEntity<FbaPurchaseExceptionService.Board> board(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.board(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.VIEW),marketplaceId));
    }
    @GetMapping("/workload")
    public ResponseEntity<FbaPurchaseExceptionService.Workload> workload(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.workload(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.VIEW),marketplaceId));
    }
    @PostMapping("/bulk-assign")
    public FbaPurchaseExceptionService.BulkAssignReceipt bulkAssign(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.BulkAssign body) {
        return service.bulkAssign(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.ASSIGN),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    /** POST because the selected row identities/versions are in the body;
     * Session Owner + CSRF still apply, but the service never mutates. */
    @PostMapping("/bulk-resolve/preflight")
    public ResponseEntity<FbaPurchaseExceptionService.ResolvePreview> preflight(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.ResolvePreviewRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preflightResolve(
                permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.RESOLVE),marketplaceId,body));
    }
    @GetMapping("/batches")
    public ResponseEntity<List<FbaPurchaseExceptionService.BatchActivity>> batches(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.recentBatches(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.VIEW),marketplaceId));
    }
    @PostMapping("/bulk-resolve")
    public FbaPurchaseExceptionService.BulkResolveReceipt bulkResolve(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.BulkResolve body) {
        return service.bulkResolve(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.RESOLVE),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/metrics")
    public ResponseEntity<FbaPurchaseExceptionService.Metrics> metrics(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.metrics(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.VIEW),marketplaceId));
    }
    @PostMapping("/cases/{lineId}/actions")
    public FbaPurchaseExceptionService.WorkItem act(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID lineId,@RequestBody FbaPurchaseExceptionService.Action body) {
        FbaExceptionPermissionService.Capability capability=switch(body.action()) {
            case "ASSIGN","SET_DUE" -> FbaExceptionPermissionService.Capability.ASSIGN;
            case "RESOLVE" -> FbaExceptionPermissionService.Capability.RESOLVE;
            case "ACKNOWLEDGE","REOPEN" -> FbaExceptionPermissionService.Capability.REVIEW;
            default -> throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,"Unsupported case action");
        };
        return service.act(permitted(auth,request,marketplaceId,capability),marketplaceId,
            access.authenticatedUser(auth),lineId,body);
    }
    @PostMapping("/bulk-acknowledge")
    public FbaPurchaseExceptionService.BulkReceipt bulk(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.Bulk body) {
        return service.bulkAcknowledge(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.REVIEW),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/cases/{lineId}/history")
    public ResponseEntity<List<FbaPurchaseExceptionService.History>> history(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID lineId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.VIEW),marketplaceId,lineId));
    }
    @PostMapping("/policy")
    public FbaPurchaseExceptionService.Policy savePolicy(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody FbaPurchaseExceptionService.SavePolicy body) {
        return service.savePolicy(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.CONFIGURE),marketplaceId,
            access.authenticatedUser(auth),body);
    }
    @GetMapping("/policy/history")
    public ResponseEntity<List<FbaPurchaseExceptionService.PolicyHistory>> policyHistory(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.policyHistory(permitted(auth,request,marketplaceId,FbaExceptionPermissionService.Capability.VIEW),marketplaceId));
    }
}
