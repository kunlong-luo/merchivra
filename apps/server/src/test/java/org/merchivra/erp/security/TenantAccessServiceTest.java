package org.merchivra.erp.security;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantAccessServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantAccessService access = new TenantAccessService(jdbc);

    private final UUID userId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID tenantA = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID tenantB = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    private Authentication user() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(userId.toString());
        return authentication;
    }

    @Test
    void rejectsUnauthenticatedAndMalformedPrincipalsBeforeQuery() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                () -> access.authenticatedUser(null)).getStatusCode());
        Authentication invalid = mock(Authentication.class);
        when(invalid.isAuthenticated()).thenReturn(true);
        when(invalid.getName()).thenReturn("not-a-user-id");
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                () -> access.authenticatedUser(invalid)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void onlyAllowsSwitchToAuthorizedTenant() {
        Authentication user = user();
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(userId)))
            .thenReturn(List.of(tenantA));
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(tenantA, access.currentTenant(user, request));

        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
            () -> access.switchTenant(user, request, tenantB));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertEquals(tenantA, access.currentTenant(user, request));
    }

    @Test
    void revocationDoesNotTrustCachedSessionSelection() {
        Authentication user = user();
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(userId)))
            .thenReturn(List.of(tenantA), List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(tenantA, access.currentTenant(user, request));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                () -> access.currentTenant(user, request)).getStatusCode());
    }

    @Test
    void selectedTenantMustBeInCurrentAllowedList() {
        Authentication user = user();
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(userId)))
            .thenReturn(List.of(tenantA), List.of(tenantA, tenantB));
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(tenantA, access.currentTenant(user, request));
        assertEquals(tenantB, access.switchTenant(user, request, tenantB));
    }
}
