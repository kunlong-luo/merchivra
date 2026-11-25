package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptCostCompositionV249Test {
    static final UUID MP=ReceiptCostIntegrityV216Test.MARKETPLACE;
    static final UUID R=ReceiptCostIntegrityV216Test.RECEIPT;
    static final UUID A=ReceiptCostIntegrityV216Test.id(24901);
    static final UUID B=ReceiptCostIntegrityV216Test.id(24902);
    static ReceiptCostCompositionServiceV249.Claim claim(UUID id,int from,int to) {
        return new ReceiptCostCompositionServiceV249.Claim(id,MP,to-from,3,3,"USD",
            new BigDecimal("10.000000"),"LOCAL_LINK_INTACT_UNVERIFIED",R,3,from,to);
    }
    static ReceiptCostIntegrityGateV216.Source expense(UUID id,String category,String amount) {
        var n=new BigDecimal(amount);
        return new ReceiptCostIntegrityGateV216.Source(id,id,"APPROVED_UNVERIFIED",
            "REVIEWED_UNVERIFIED","USD",category,n,"USD",n,n,n,1,1,1,0,true,true,true);
    }
    static ReceiptCostIntegrityServiceV216.Snapshot source(boolean duty,boolean collide) {
        var a=expense(A,"INBOUND_FREIGHT","10");
        var b=expense(B,"IMPORT_DUTY","5");
        var flags=new java.util.HashMap<UUID,CostIdentityReconciliationService.Preview>();
        flags.put(A,CostIdentityReconciliationService.summarize(A,"REVIEWED_UNVERIFIED",
            List.of(collide,false,false,false,false,false,false,false)));
        if(duty)flags.put(B,CostIdentityReconciliationService.summarize(B,"REVIEWED_UNVERIFIED",
            List.of(false,false,false,false,false,false,false,false)));
        return new ReceiptCostIntegrityServiceV216.Snapshot(duty?List.of(a,b):List.of(a),flags,1);
    }
    @Test void purchaseAndEachReceiptExpenseConserveOriginalAmount() {
        var c=ReceiptCostCompositionServiceV249.compose(MP,R,source(true,false),
            List.of(claim(A,0,1),claim(B,2,3)),false);
        assertEquals("LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED",c.state());
        assertEquals("45.000000",c.receiptCandidateCost());
        assertEquals("30.000000",c.allocatedCandidateCost());
        assertEquals("15.000000",c.unallocatedCandidateCost());
        assertEquals(List.of("14.999999","15.000001"),
            c.saleShares().stream().map(ReceiptCostCompositionServiceV249.SaleShare::candidateCost).toList());
        assertEquals(3,c.components().size());
        assertFalse(c.actualBatchCogsVerified());
        assertNull(c.actualBatchCogs());
        assertNull(c.realizedNetProfit());
    }
    @Test void missingCostOrCollisionOrCrossMarketplaceCannotReturnPartialTotal() {
        var claims=List.of(claim(A,0,1));
        for(var c:List.of(
            ReceiptCostCompositionServiceV249.compose(MP,R,source(false,false),claims,false),
            ReceiptCostCompositionServiceV249.compose(MP,R,source(true,true),claims,false),
            ReceiptCostCompositionServiceV249.compose(MP,R,source(true,false),claims,true),
            ReceiptCostCompositionServiceV249.compose(MP,R,source(true,false),List.of(),false))) {
            assertEquals("LOCAL_COST_COMPOSITION_HOLD",c.state());
            assertNull(c.receiptCandidateCost());
            assertTrue(c.saleShares().isEmpty());
        }
    }
    @Test void missingRangeOrRejectedUnitsCannotInventBasis() {
        var a=claim(A,0,1);
        var missing=new ReceiptCostCompositionServiceV249.Claim(A,MP,1,3,3,"USD",
            a.price(),a.state(),null,null,null,null);
        var rejected=new ReceiptCostCompositionServiceV249.Claim(A,MP,1,3,4,"USD",
            a.price(),a.state(),R,3,0,1);
        for(var x:List.of(missing,rejected)) {
            var c=ReceiptCostCompositionServiceV249.compose(MP,R,source(true,false),
                List.of(x),false);
            assertNull(c.receiptCandidateCost());
        }
        assertThrows(IllegalArgumentException.class,()->ReceiptCostCompositionServiceV249
            .compose(MP,R,source(true,false),List.of(claim(A,0,2),claim(B,1,3)),false));
    }
}
