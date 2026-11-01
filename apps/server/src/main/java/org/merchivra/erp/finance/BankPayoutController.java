package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Manual bank payout evidence staging. Owner session + CSRF, never a bank
 * connector or a payout verification endpoint.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}")
public class BankPayoutController {
    private final TenantAccessService access;
    private final BankPayoutImportService imports;
    private final BankPayoutComparisonService comparisons;
    public BankPayoutController(TenantAccessService access,
        BankPayoutImportService imports,BankPayoutComparisonService comparisons) {
        this.access=access;this.imports=imports;this.comparisons=comparisons;
    }

    @GetMapping("/bank-payout-imports")
    public ResponseEntity<List<BankPayoutImportService.Import>> list(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(imports.list(access.currentTenant(auth,request),marketplaceId));
    }

    @PostMapping(path="/bank-payout-imports/{statementRef}",
        consumes=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<BankPayoutImportService.Import> upload(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable String statementRef,@RequestBody byte[] csv) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(imports.importCsv(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth),statementRef,csv));
    }

    @GetMapping("/bank-payout-imports/{batchId}/entries")
    public ResponseEntity<List<BankPayoutImportService.Payout>> lines(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID batchId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(imports.lines(access.currentTenant(auth,request),marketplaceId,batchId));
    }

    @GetMapping("/reports/{reportId}/bank-comparison")
    public ResponseEntity<BankPayoutComparisonService.Result> compare(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(comparisons.compare(access.currentTenant(auth,request),
                marketplaceId,reportId));
    }
}
