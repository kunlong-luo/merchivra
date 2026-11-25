package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefundOriginalCostSliceV255Test {
    private static final UUID TENANT=UUID.randomUUID(),ORDER=UUID.randomUUID(),ITEM=UUID.randomUUID();
    private static final UUID A=ReceiptCostCompositionV249Test.A;
    private static final UUID MP=ReceiptCostCompositionV249Test.MP,R=ReceiptCostCompositionV249Test.R;
    private static final RefundOriginalCostSliceEngineV255.Identity ID=
        new RefundOriginalCostSliceEngineV255.Identity(TENANT,MP,R,A,ORDER,ITEM);
    private static final UUID FIRST=UUID.randomUUID(),SECOND=UUID.randomUUID();
    static RefundOriginalCostSliceEngineV255.Basis basis(){
        var candidate=ReceiptCostCompositionServiceV249.compose(MP,R,
            ReceiptCostCompositionV249Test.source(true,false),
            List.of(ReceiptCostCompositionV249Test.claim(A,0,3)),false);
        return RefundOriginalCostSliceEngineV255.capture(ID,candidate);
    }
    static RefundOriginalCostSliceEngineV255.Slice slice(UUID event,int from,int to){
        return new RefundOriginalCostSliceEngineV255.Slice(event,ITEM,from,to,to-from,"RESTOCKED_CLAIMED");
    }
    @Test void partialAndFullReplayUseOriginalReceiptTailNotNewAverage(){
        var b=basis();
        assertEquals("45.000000",b.originalCandidateAmount());
        var first=RefundOriginalCostSliceEngineV255.replay(ID,b.sha256(),b,List.of(slice(FIRST,0,1)));
        assertEquals("14.999999",first.refundCandidateAmount());
        assertEquals("30.000001",first.remainingCandidateAmount());
        var full=RefundOriginalCostSliceEngineV255.replay(ID,b.sha256(),b,
            List.of(slice(SECOND,1,3),slice(FIRST,0,1)));
        assertEquals("45.000000",full.refundCandidateAmount());
        assertEquals("0.000000",full.remainingCandidateAmount());
        assertEquals(3,full.candidateQuantity());
        assertEquals("30.000001",full.events().get(1).candidateAmount());
        assertFalse(full.refundCogsReversalAllowed());
        assertNull(full.actualRefundCogsReversal());
        assertNull(full.realizedNetProfit());
        assertEquals(full,RefundOriginalCostSliceEngineV255.replay(ID,b.sha256(),b,
            List.of(slice(FIRST,0,1),slice(SECOND,1,3))));
    }
    @Test void rejectsUnknownPopulationOverlapOutOfBoundsRepeatedEventsAndWrongItem(){
        var b=basis();
        for(var proposed:List.of(
            List.<RefundOriginalCostSliceEngineV255.Slice>of(),
            List.of(slice(FIRST,0,2),slice(SECOND,1,3)),
            List.of(slice(FIRST,2,4)),
            List.of(slice(FIRST,0,1),slice(FIRST,1,2)),
            List.of(new RefundOriginalCostSliceEngineV255.Slice(FIRST,UUID.randomUUID(),0,1,1,"RESTOCKED_CLAIMED")),
            List.of(new RefundOriginalCostSliceEngineV255.Slice(FIRST,ITEM,0,1,1,"NOT_RESTOCKED")),
            List.of(new RefundOriginalCostSliceEngineV255.Slice(FIRST,ITEM,0,1,2,"RESTOCKED_CLAIMED"))
        ))assertThrows(IllegalArgumentException.class,()->RefundOriginalCostSliceEngineV255
            .replay(ID,b.sha256(),b,proposed));
    }
    @Test void digestBindsIdentityAndExactOriginalSourceEvenIfReordered(){
        var b=basis();
        var reordered=new ArrayList<>(b.sources());
        java.util.Collections.reverse(reordered);
        var same=new RefundOriginalCostSliceEngineV255.Basis(b.version(),ID,b.currency(),b.basisQuantity(),
            b.originalFrom(),b.originalTo(),reordered,b.originalCandidateAmount(),b.sha256());
        assertEquals("14.999999",RefundOriginalCostSliceEngineV255.replay(ID,b.sha256(),same,
            List.of(slice(FIRST,0,1))).refundCandidateAmount());
        var changed=new RefundOriginalCostSliceEngineV255.Identity(UUID.randomUUID(),MP,R,A,ORDER,ITEM);
        assertThrows(IllegalArgumentException.class,()->RefundOriginalCostSliceEngineV255
            .replay(changed,b.sha256(),b,List.of(slice(FIRST,0,1))));
        var forged=new RefundOriginalCostSliceEngineV255.Basis(b.version(),ID,b.currency(),b.basisQuantity(),
            b.originalFrom(),b.originalTo(),b.sources(),"45.000001",b.sha256());
        assertThrows(IllegalArgumentException.class,()->RefundOriginalCostSliceEngineV255
            .replay(ID,b.sha256(),forged,List.of(slice(FIRST,0,1))));
        assertThrows(IllegalArgumentException.class,()->RefundOriginalCostSliceEngineV255
            .replay(ID,"0".repeat(64),b,List.of(slice(FIRST,0,1))));
    }
}
