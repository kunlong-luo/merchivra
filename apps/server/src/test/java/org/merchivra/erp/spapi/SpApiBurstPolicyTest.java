package org.merchivra.erp.spapi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpApiBurstPolicyTest {
    @Test
    void defaultsToOneAndAllowsOnlyExplicitReviewedOperations() {
        var defaults=new SpApiBurstPolicy("");
        assertEquals(1,defaults.capacity(SpApiOperation.SEARCH_ORDERS));
        var approved=new SpApiBurstPolicy("SEARCH_ORDERS=2,GET_FBA_INVENTORY=3");
        assertEquals(2,approved.capacity(SpApiOperation.SEARCH_ORDERS));
        assertEquals(3,approved.capacity(SpApiOperation.GET_FBA_INVENTORY));
        assertEquals(1,approved.capacity(SpApiOperation.GET_ORDER));
    }

    @Test
    void rejectsInvalidOrUnreviewedBurstMappingsAtStartup() {
        for(String input: new String[]{
            "UNKNOWN_OPERATION=3","CREATE_REPORT=2","SEARCH_ORDERS=1",
            "SEARCH_ORDERS=11","SEARCH_ORDERS=2,SEARCH_ORDERS=3",
            "GET_ORDER=-1","GET_ORDER=x","GET_ORDER:3","GET_ORDER=2,"
        }) {
            assertThrows(IllegalArgumentException.class,()->new SpApiBurstPolicy(input),
                input);
        }
    }
}
