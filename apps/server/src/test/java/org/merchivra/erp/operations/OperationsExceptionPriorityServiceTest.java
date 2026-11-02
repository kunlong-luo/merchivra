package org.merchivra.erp.operations;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class OperationsExceptionPriorityServiceTest {
    @Test void hardSourceAndHumanReviewRiskFloorsCannotBeDowngraded() {
        assertEquals("P0",OperationsExceptionPriorityService.validatePriority(
            "SOURCE_CHANGED","P0"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority("SOURCE_CHANGED","P1"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority("PAST_DUE","P3"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority(
                "INDEPENDENT_IDENTITY_REVIEW","P2"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority(
                "LOCAL_VARIANCE_REVIEW","P3"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority("DUE_SOON","P2"));
    }
    @Test void routineAndUnassignedMayEscalateButNotAssignOrAutoClose() {
        assertEquals("P0",OperationsExceptionPriorityService.validatePriority(
            "NEEDS_OWNER","P0"));
        assertEquals("P1",OperationsExceptionPriorityService.validatePriority(
            "SOURCE_WARNING","P1"));
        assertEquals("P3",OperationsExceptionPriorityService.validatePriority(
            "ROUTINE_FOLLOWUP","P3"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority("NEEDS_OWNER","P3"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority("UNKNOWN","P0"));
        assertThrows(ResponseStatusException.class,()->
            OperationsExceptionPriorityService.validatePriority("NEEDS_OWNER","URGENT"));
    }
}
