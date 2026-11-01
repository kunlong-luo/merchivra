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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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
    void contractDocsStayDeniedEvenForAuthenticatedSessionByDefault() throws Exception {
        mvc.perform(get("/v3/api-docs/finance-profits")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user("a-local-owner")))
            .andExpect(status().isForbidden());
    }

    @Test
    void allNewBusinessModulesDenyAnonymousReads() throws Exception {
        for (String path : new String[] {
            "/api/v1/operations/findings",
            "/api/v1/operations/summary",
            "/api/v1/operations/marketplaces/11111111-1111-4111-8111-111111111111/rules",
            "/api/v1/operations/marketplaces/11111111-1111-4111-8111-111111111111/rules/audit",
            "/api/v1/operations/findings/11111111-1111-4111-8111-111111111111/attention-audit",
            "/api/v1/ads/marketplaces/11111111-1111-4111-8111-111111111111/reports",
            "/api/v1/fba/marketplaces/11111111-1111-4111-8111-111111111111/shipments",
            "/api/v1/finance/marketplaces/11111111-1111-4111-8111-111111111111/exceptions"
        }) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersAndNonCacheableResponsesRemainInPlace() throws Exception {
        mvc.perform(get("/api/v1/ping"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("X-Frame-Options","DENY"))
            .andExpect(header().string("Cache-Control",
                org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void newModuleMutationsRequireCsrfEvenForAuthenticatedOwner() throws Exception {
        for(String path : new String[]{
            "/api/v1/operations/scan",
            "/api/v1/operations/marketplaces/11111111-1111-4111-8111-111111111111/rules/LOW_STOCK",
            "/api/v1/operations/findings/11111111-1111-4111-8111-111111111111/claim",
            "/api/v1/operations/findings/11111111-1111-4111-8111-111111111111/snooze",
            "/api/v1/ads/marketplaces/11111111-1111-4111-8111-111111111111/profiles",
            "/api/v1/fba/marketplaces/11111111-1111-4111-8111-111111111111/shipments"
        }) mvc.perform(post(path).with(user("local-owner")))
           .andExpect(status().isForbidden());
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
