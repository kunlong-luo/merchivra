package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-only manually unverified advertising expense references and SKU allocation. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/ads-spend")
public class AdSpendAttributionController {
    private final TenantAccessService access;
    private final AdSpendAttributionService service;
    public AdSpendAttributionController(TenantAccessService access,AdSpendAttributionService service) {
        this.access=access;this.service=service;
    }
    public record SourceBody(String sourceKey,String campaignReference,
        LocalDate businessDate,String spendAmount,String currency) {}
    public record ShareBody(UUID skuId,int basisPoints,String evidenceReference) {}
    @GetMapping
    public ResponseEntity<List<AdSpendAttributionService.Source>> list(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(access.currentTenant(auth,req),marketplaceId));
    }
    @PostMapping
    public ResponseEntity<AdSpendAttributionService.Source> create(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @RequestBody SourceBody value) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(service.create(access.currentTenant(auth,req),marketplaceId,
                access.authenticatedUser(auth),value.sourceKey(),value.campaignReference(),
                value.businessDate(),value.spendAmount(),value.currency()));
    }
    @GetMapping("/sku-options")
    public ResponseEntity<List<AdSpendAttributionService.Option>> skus(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.skuOptions(access.currentTenant(auth,req),marketplaceId));
    }
    @GetMapping("/{sourceId}/preview")
    public ResponseEntity<AdSpendAttributionService.View> preview(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,req),marketplaceId,sourceId));
    }
    @PostMapping("/{sourceId}/attributions")
    public ResponseEntity<AdSpendAttributionService.View> assign(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID sourceId,@RequestBody ShareBody input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.assign(access.currentTenant(auth,req),marketplaceId,
                access.authenticatedUser(auth),sourceId,input.skuId(),
                input.basisPoints(),input.evidenceReference()));
    }
    @DeleteMapping("/{sourceId}/attributions/{skuId}")
    public ResponseEntity<Void> withdraw(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID sourceId,@PathVariable UUID skuId) {
        service.withdraw(access.currentTenant(auth,req),marketplaceId,
            access.authenticatedUser(auth),sourceId,skuId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{sourceId}/audit")
    public ResponseEntity<List<AdSpendAttributionService.Audit>> audit(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(auth,req),marketplaceId,sourceId));
    }
}
