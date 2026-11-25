package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;

/** Owner-only local candidate view, not a ledger or actual COGS endpoint. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/purchase-receipts/{receiptId}")
public class ReceiptCostCompositionControllerV252 {
    private final TenantAccessService access;
    private final ReceiptCostCompositionServiceV249 service;
    public ReceiptCostCompositionControllerV252(TenantAccessService access,
        ReceiptCostCompositionServiceV249 service) {
        this.access=access;this.service=service;
    }

    // Return bounded source summaries instead of duplicating every sale range
    // for every expense (up to 51*1000 shares inside the calculation).
    public record SourceSummary(String sourceId,String category,
        String originalCurrency,int basisQuantity,int allocatedQuantity,
        int unallocatedQuantity,String receiptCandidateAmount,
        String allocatedCandidateAmount,String unallocatedCandidateAmount) {}
    public record ReceiptCostCandidateV252(String marketplaceId,String purchaseReceiptId,
        String state,String originalCurrency,String receiptCandidateCost,
        String allocatedCandidateCost,String unallocatedCandidateCost,
        List<SourceSummary> components,
        List<ReceiptCostCompositionServiceV249.SaleShare> saleShares,
        List<String> blockerCodes,boolean completeExternalCostCoverage,
        boolean actualBatchCogsVerified,String actualBatchCogs,
        boolean postingAllowed,String realizedNetProfit) {}

    @Operation(operationId="getPurchaseReceiptCostCandidateV252")
    @GetMapping(value="/cost-composition-candidate",produces="application/json")
    public ResponseEntity<ReceiptCostCandidateV252> preview(Authentication auth,
        HttpServletRequest request,@PathVariable UUID marketplaceId,@PathVariable UUID receiptId) {
        // Do not silently switch a staff workspace to another tenant in which
        // the caller is Owner. Check the selected readable tenant explicitly.
        UUID tenant=access.currentReadTenant(auth,request);
        if(!access.isOwner(auth,tenant))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var value=service.preview(tenant,marketplaceId,receiptId);
        var components=value.components().stream().map(component->{
            var p=component.partition();
            return new SourceSummary(component.sourceId(),component.category(),
                p.currency(),p.basisQuantity(),p.allocatedQuantity(),p.unallocatedQuantity(),
                p.sourceAmount(),p.allocatedAmount(),p.unallocatedAmount());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new ReceiptCostCandidateV252(marketplaceId.toString(),receiptId.toString(),
                value.state(),value.currency(),value.receiptCandidateCost(),
                value.allocatedCandidateCost(),value.unallocatedCandidateCost(),
                components,value.saleShares(),value.blockerCodes(),false,false,null,false,null));
    }
}
