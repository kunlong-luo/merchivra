package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

/** Local customer-return intake candidates. No trusted WMS, stock or GL writes. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events/{eventId}/return-intakes")
public class CustomerReturnIntakeController {
    private final TenantAccessService access;
    private final CustomerReturnIntakeService service;
    public CustomerReturnIntakeController(TenantAccessService access,
        CustomerReturnIntakeService service) {
        this.access=access;this.service=service;
    }
    @Operation(operationId="getLocalCustomerReturnIntakes")
    @GetMapping
    public ResponseEntity<CustomerReturnIntakeService.Page> report(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @RequestParam(required=false) OffsetDateTime beforeAt,
        @RequestParam(required=false) UUID beforeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.report(access.currentTenant(auth,request),marketplaceId,
                eventId,beforeAt,beforeId));
    }
    @Operation(operationId="requestLocalCustomerReturnIntake")
    @PostMapping
    public ResponseEntity<CustomerReturnIntakeService.Intake> create(
        @Parameter(hidden=true) Authentication auth,
        @Parameter(hidden=true) HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @RequestBody CustomerReturnIntakeService.Request body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.request(access.currentTenant(auth,request),marketplaceId,
                eventId,access.authenticatedUser(auth),body));
    }
    @PostMapping("/{intakeId}/attest")
    public ResponseEntity<CustomerReturnIntakeService.Intake> attest(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,@RequestBody CustomerReturnIntakeService.Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,
                eventId,intakeId,access.authenticatedUser(auth),true,body.reason()));
    }
    @PostMapping("/{intakeId}/reject")
    public ResponseEntity<CustomerReturnIntakeService.Intake> reject(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId,@RequestBody CustomerReturnIntakeService.Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.decide(access.currentTenant(auth,request),marketplaceId,
                eventId,intakeId,access.authenticatedUser(auth),false,body.reason()));
    }
    @GetMapping("/{intakeId}/audit")
    public ResponseEntity<List<CustomerReturnIntakeService.Audit>> audit(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID eventId,
        @PathVariable UUID intakeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.history(access.currentTenant(auth,request),marketplaceId,
                eventId,intakeId));
    }
}
