package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Session Owner only. Interpretation rules are local, audited, and never
 * create or change source facts, settlements, accounting posts or Amazon data.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}")
public class FinanceClassificationController {
    private final TenantAccessService access;
    private final FinanceClassificationService classification;

    public FinanceClassificationController(TenantAccessService access,
        FinanceClassificationService classification) {
        this.access=access;this.classification=classification;
    }
    public record RuleBody(String sourceType,String category) {}

    @GetMapping("/classification-rules")
    public ResponseEntity<List<FinanceClassificationService.Rule>> rules(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(classification.rules(access.currentTenant(auth,request),marketplaceId));
    }
    @PostMapping("/classification-rules")
    public ResponseEntity<FinanceClassificationService.Rule> upsert(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @RequestBody RuleBody body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(classification.upsert(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),body.sourceType(),body.category()));
    }
    @DeleteMapping("/classification-rules/{ruleId}")
    public ResponseEntity<FinanceClassificationService.Rule> deactivate(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID ruleId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(classification.deactivate(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),ruleId));
    }
    @GetMapping("/classification-rules/{ruleId}/audit")
    public ResponseEntity<List<FinanceClassificationService.Audit>> audit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID ruleId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(classification.history(access.currentTenant(auth,request),marketplaceId,ruleId));
    }
    @GetMapping("/events/{eventId}/classification-preview")
    public ResponseEntity<FinanceClassificationService.EventPreview> preview(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(classification.preview(access.currentTenant(auth,request),marketplaceId,eventId));
    }
}
