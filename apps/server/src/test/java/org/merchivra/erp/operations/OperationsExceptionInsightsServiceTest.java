package org.merchivra.erp.operations;

import org.junit.jupiter.api.Test;
import org.merchivra.erp.fba.FbaPurchaseExceptionService;
import static org.junit.jupiter.api.Assertions.*;

class OperationsExceptionInsightsServiceTest {
    private static FbaPurchaseExceptionService.WorkItem line(String finding,
        String severity,String state,String reminder,boolean stale,String assignee) {
        return new FbaPurchaseExceptionService.WorkItem(
            "33333333-3333-4333-8333-333333333333",
            "22222222-2222-4222-8222-222222222222",
            "LOCAL-EXAMPLE","SKU-A",finding,severity,"a".repeat(64),
            state,assignee,null,reminder,stale,1,null);
    }
    @Test void driftAlwaysTakesPrecedenceEvenWhenLocallyResolved() {
        var decision=OperationsExceptionInsightsService.decide(
            line("NONE","INFO","RESOLVED","RECHECK",true,null));
        assertEquals("P0",decision.priority());
        assertEquals("SOURCE_CHANGED",decision.code());
    }
    @Test void overdueIsUrgentButNotAnAmazonCarrierSla() {
        var result=OperationsExceptionInsightsService.decide(
            line("NONE","INFO","ACKNOWLEDGED","OVERDUE",false,null));
        assertEquals("P0",result.priority());
        assertEquals("PAST_DUE",result.code());
        assertFalse(result.nextStep().contains("Amazon"));
    }
    @Test void mandatoryHumanEvidenceGatesPrecedeBalancedAssignment() {
        assertEquals("INDEPENDENT_IDENTITY_REVIEW",
            OperationsExceptionInsightsService.decide(
                line("PENDING_IDENTITY_REVIEW","HIGH","OPEN","NONE",false,null)).code());
        assertEquals("LOCAL_VARIANCE_REVIEW",
            OperationsExceptionInsightsService.decide(
                line("LOCAL_OVER_REPORT","HIGH","OPEN","NONE",false,null)).code());
        assertEquals("DUE_SOON",OperationsExceptionInsightsService.decide(
                line("NONE","INFO","OPEN","DUE_SOON",false,null)).code());
    }
    @Test void missingOwnerAndRoutineAreAdvisoryOnly() {
        assertEquals("NEEDS_OWNER",OperationsExceptionInsightsService.decide(
            line("UNLINKED_DRAFT","INFO","OPEN","NONE",false,null)).code());
        assertEquals("ROUTINE_FOLLOWUP",OperationsExceptionInsightsService.decide(
            line("NONE","INFO","ACKNOWLEDGED","NONE",false,
                     "44444444-4444-4444-8444-444444444444")).code());
        assertEquals("HIGH_SEVERITY",OperationsExceptionInsightsService.decide(
            line("OTHER","HIGH","OPEN","NONE",false,null)).code());
    }
}
