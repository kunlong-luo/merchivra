package org.merchivra.erp.demo;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DemoReadEndpointsTest {
    private final DemoController controller = new DemoController(new MockMarketplaceOverviewGateway());

    @Test
    void scopesOrdersByKnownMockAccount() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/demo/orders")
                .param("accountId", MockMarketplaceOverviewGateway.US_ACCOUNT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].provisionalSales.currency").value("USD"));
        mvc.perform(get("/api/v1/demo/orders")
                .param("accountId", MockMarketplaceOverviewGateway.EU_ACCOUNT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].provisionalSales.currency").value("EUR"));
    }

    @Test
    void unknownAccountNeverFallsBackToAllRecords() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/api/v1/demo/orders").param("accountId", "unknown"))
            .andExpect(status().isNotFound());
    }

    @Test
    void inventoryPreservesMissingQuantityAsJsonNull() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/api/v1/demo/inventory/fba")
                .param("accountId", MockMarketplaceOverviewGateway.US_ACCOUNT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[1].quality").value("UNKNOWN"))
            .andExpect(jsonPath("$[1].fulfillable").value(org.hamcrest.Matchers.nullValue()));
    }
}
