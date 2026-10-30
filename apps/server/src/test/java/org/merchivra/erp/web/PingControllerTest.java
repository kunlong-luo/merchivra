package org.merchivra.erp.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PingControllerTest {

    @Test
    void pingIsAvailable() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PingController()).build();
        mvc.perform(get("/api/v1/ping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("merchivra"))
            .andExpect(jsonPath("$.stage").value("M0"));
    }
}
