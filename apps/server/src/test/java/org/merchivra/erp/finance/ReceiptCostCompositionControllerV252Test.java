package org.merchivra.erp.finance;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceiptCostCompositionControllerV252Test {
    @Test void ownerGetsCompactCandidateNoStoreNeverAccountingTruth() {
        var access=mock(TenantAccessService.class);
        var service=mock(ReceiptCostCompositionServiceV249.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),market=UUID.randomUUID(),receipt=UUID.randomUUID(),
            claim=UUID.randomUUID();
        when(access.currentReadTenant(auth,request)).thenReturn(tenant);
        when(access.isOwner(auth,tenant)).thenReturn(true);
        var partition=CostAllocationEngine.partitionReceiptAmount("45","USD",3,
            List.of(new CostAllocationEngine.ReceiptRange(claim,0,1)));
        var c=new ReceiptCostCompositionServiceV249.Candidate(
            "LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED","USD","45.000000",
            "15.000000","30.000000",
            List.of(new ReceiptCostCompositionServiceV249.Component(null,"PURCHASE_PO_REFERENCE",partition)),
            List.of(new ReceiptCostCompositionServiceV249.SaleShare(claim.toString(),"15.000000")),
            List.of("EXTERNAL_COST_COVERAGE_UNPROVEN"),false,null,null);
        when(service.preview(tenant,market,receipt)).thenReturn(c);
        var response=new ReceiptCostCompositionControllerV252(access,service).preview(auth,request,market,receipt);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var value=response.getBody();
        assertEquals(market.toString(),value.marketplaceId());
        assertEquals(receipt.toString(),value.purchaseReceiptId());
        assertEquals("45.000000",value.receiptCandidateCost());
        assertEquals("15.000000",value.components().getFirst().allocatedCandidateAmount());
        assertEquals(1,value.saleShares().size());
        assertFalse(value.completeExternalCostCoverage());
        assertFalse(value.actualBatchCogsVerified());
        assertFalse(value.postingAllowed());
        assertNull(value.actualBatchCogs());
        assertNull(value.realizedNetProfit());
        verify(service).preview(tenant,market,receipt);
    }

    @Test void staffInSelectedTenantCannotReadEvenIfOwnerElsewhere() {
        var access=mock(TenantAccessService.class);
        var service=mock(ReceiptCostCompositionServiceV249.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),market=UUID.randomUUID(),receipt=UUID.randomUUID();
        when(access.currentReadTenant(auth,request)).thenReturn(tenant);
        when(access.isOwner(auth,tenant)).thenReturn(false);
        assertEquals(403,assertThrows(ResponseStatusException.class,
            ()->new ReceiptCostCompositionControllerV252(access,service)
                .preview(auth,request,market,receipt)).getStatusCode().value());
        verifyNoInteractions(service);
        verify(access,never()).currentTenant(auth,request);
    }
}
