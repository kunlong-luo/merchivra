package org.merchivra.erp.security;

import org.junit.jupiter.api.Test;
import org.merchivra.erp.web.PingController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast MVC slice check: no actual PostgreSQL, browser or E2E setup required.
 * Checks that the configured filter chain fails closed for business routes.
 */
@WebMvcTest(controllers = PingController.class)
@Import(SecurityConfig.class)
class SecurityHttpBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean TenantAccessService tenants;

    @Test
    void healthPingIsPublic() throws Exception {
        mvc.perform(get("/api/v1/ping")).andExpect(status().isOk());
    }

    @Test
    void unauthenticatedBusinessAndDemoDataCannotBeRead() throws Exception {
        mvc.perform(get("/api/v1/seller-accounts"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/demo/overview"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangingRequestsWithoutCsrfTokenAreRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .param("username", "local-admin")
                .param("password", "invalid"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demo/sync-runs"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isForbidden());
    }
}
