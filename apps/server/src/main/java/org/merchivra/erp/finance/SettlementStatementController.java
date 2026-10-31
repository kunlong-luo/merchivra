package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Local Owner-only reconciliation workflow: never touches Amazon or bank services. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/settlements")
public class SettlementStatementController {
    private final TenantAccessService access;
    private final SettlementStatementService service;
    public SettlementStatementController(TenantAccessService access,
        SettlementStatementService service) {
        this.access=access;this.service=service;
    }
    public record Create(String reference,OffsetDateTime periodStart,
        OffsetDateTime periodEnd,String reportedNet,String currency) {}

    @GetMapping
    public ResponseEntity<List<SettlementStatementService.Statement>> list(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(access.currentTenant(auth,req),marketplaceId));
    }
    @PostMapping
    public ResponseEntity<SettlementStatementService.Statement> create(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @RequestBody Create data) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(service.create(access.currentTenant(auth,req),marketplaceId,
                access.authenticatedUser(auth),data.reference(),
                data.periodStart(),data.periodEnd(),data.reportedNet(),data.currency()));
    }
    @GetMapping("/{statementId}/candidates")
    public ResponseEntity<List<SettlementStatementService.Candidate>> candidates(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID statementId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.candidates(access.currentTenant(auth,req),marketplaceId,statementId));
    }
    @GetMapping("/{statementId}/preview")
    public ResponseEntity<SettlementReconciliationEngine.Preview> preview(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID statementId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,req),marketplaceId,statementId));
    }
    @PostMapping("/{statementId}/events/{eventId}")
    public ResponseEntity<Void> link(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID statementId,@PathVariable UUID eventId) {
        service.link(access.currentTenant(auth,req),marketplaceId,
            access.authenticatedUser(auth),statementId,eventId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @DeleteMapping("/{statementId}/events/{eventId}")
    public ResponseEntity<Void> unlink(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID statementId,@PathVariable UUID eventId) {
        service.unlink(access.currentTenant(auth,req),marketplaceId,
            access.authenticatedUser(auth),statementId,eventId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{statementId}/audit")
    public ResponseEntity<List<SettlementStatementService.Audit>> audit(
        Authentication auth,HttpServletRequest req,@PathVariable UUID marketplaceId,
        @PathVariable UUID statementId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.auditHistory(access.currentTenant(auth,req),
                marketplaceId,statementId));
    }
}
