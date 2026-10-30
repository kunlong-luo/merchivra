package org.merchivra.erp.demo;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.demo.DemoModels.Overview;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MockMarketplaceOverviewGatewayTest {
    private final MockMarketplaceOverviewGateway gateway = new MockMarketplaceOverviewGateway();

    @Test
    void sourceDataAndSalesUseIndependentCurrencyScopes() {
        Overview result = gateway.getOverview();
        assertEquals("MOCK", result.mode());
        assertEquals(2, result.accounts().size());
        assertEquals(2, result.summaries().size());
        assertEquals("63.00", result.summaries().get(0).provisionalSales().amount());
        assertEquals("USD", result.summaries().get(0).currency());
        assertEquals("20.00", result.summaries().get(1).provisionalSales().amount());
        assertEquals("EUR", result.summaries().get(1).currency());
        assertEquals("8.00", result.summaries().get(0).observedRefunds().amount());
    }

    @Test
    void refundAndFulfillmentAreIndependent() {
        var order = gateway.getOverview().orders().get(0);
        assertEquals("SHIPPED", order.fulfillmentStatus());
        assertEquals("PARTIAL", order.refundStatus());
        assertEquals("NOT_OBSERVED", order.financialStatus());
        assertEquals(new BigDecimal("8.00"), new BigDecimal(order.refundAmount().amount()));
    }

    @Test
    void unknownInventoryIsNotZeroAndUnmappedListingStillExists() {
        var result = gateway.getOverview();
        assertNull(result.inventory().get(1).fulfillable());
        assertEquals("UNKNOWN", result.inventory().get(1).quality());
        assertEquals(0, result.inventory().get(2).fulfillable());
        assertEquals("UNMAPPED", result.listings().get(1).mappingStatus());
        assertNull(result.listings().get(1).internalSku());
        assertEquals(1, result.summaries().get(0).unknownInventoryCount());
        assertEquals(1, result.summaries().get(0).unmappedListings());
    }

    @Test
    void readOnlyControllerReturnsExplicitMockMode() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoController(gateway)).build();
        mvc.perform(get("/api/v1/demo/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("MOCK"))
            .andExpect(jsonPath("$.accounts.length()").value(2))
            .andExpect(jsonPath("$.summaries[0].financialCompleteness").value("NOT_OBSERVED"))
            .andExpect(jsonPath("$.inventory[1].quality").value("UNKNOWN"));
    }
}
