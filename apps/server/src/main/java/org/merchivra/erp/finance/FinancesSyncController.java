package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner Session + CSRF. Only Marketplace UUID; never accepts Seller secrets or URLs. */
@RestController
@RequestMapping("/api/v1/finances-sync")
@ConditionalOnProperty(prefix="merchivra.finances-sync",name="enabled",havingValue="true")
public class FinancesSyncController {
 private final TenantAccessService access;
 private final FinancesSyncService service;
 public FinancesSyncController(TenantAccessService access,FinancesSyncService service){
  this.access=access;this.service=service;
 }
 @GetMapping("/{marketplaceId}")
 public ResponseEntity<FinancesSyncService.Status> status(Authentication auth,
   HttpServletRequest request,@PathVariable UUID marketplaceId){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
    .body(service.status(access.currentTenant(auth,request),marketplaceId));
 }
 @PostMapping("/{marketplaceId}/start")
 public ResponseEntity<FinancesSyncService.Status> start(Authentication auth,
   HttpServletRequest request,@PathVariable UUID marketplaceId){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
    .body(service.start(access.currentTenant(auth,request),marketplaceId));
 }
 @PostMapping("/{marketplaceId}/step")
 public ResponseEntity<FinancesSyncService.Status> step(Authentication auth,
   HttpServletRequest request,@PathVariable UUID marketplaceId){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
    .body(service.step(access.currentTenant(auth,request),marketplaceId));
 }
}
