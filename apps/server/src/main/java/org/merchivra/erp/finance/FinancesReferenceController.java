package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-only source document IDs, not a bank or settlement confirmation. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/events")
public class FinancesReferenceController {
 private final TenantAccessService access;
 private final FinancesReferenceService references;
 public FinancesReferenceController(TenantAccessService access,
   FinancesReferenceService references) {
   this.access=access;this.references=references;
 }
 @GetMapping("/{eventId}/references")
 public ResponseEntity<List<FinancesReferenceService.Reference>> list(
   Authentication auth,HttpServletRequest request,
   @PathVariable UUID marketplaceId,@PathVariable UUID eventId) {
   return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
     references.list(access.currentTenant(auth,request),marketplaceId,eventId));
 }
}
