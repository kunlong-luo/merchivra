package org.merchivra.erp.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketplaceReadControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantAccessService tenants = mock(TenantAccessService.class);
    private final MarketplaceReadController controller = new MarketplaceReadController(jdbc, tenants);
    private final Authentication auth = mock(Authentication.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final UUID tenantId = UUID.fromString("c11e7a00-1234-4234-8234-111111111111");
    private final UUID marketplaceId = UUID.fromString("c11e7a00-1234-4234-8234-222222222222");

    private void allow() {
        when(tenants.authenticatedUser(auth)).thenReturn(UUID.fromString("88888888-8888-4888-8888-888888888888"));
        when(tenants.currentReadTenant(eq(auth), any(HttpServletRequest.class))).thenReturn(tenantId);
        when(jdbc.queryForObject(anyString(), eq(Integer.class),
            eq(marketplaceId), eq(tenantId), eq(tenantId), any(UUID.class))).thenReturn(1);
    }

    @Test
    void neverAcceptsForeignMarketplaceWithoutAuthorizedTenant() {
        when(tenants.authenticatedUser(auth)).thenReturn(UUID.fromString("88888888-8888-4888-8888-888888888888"));
        when(tenants.currentReadTenant(eq(auth), any(HttpServletRequest.class))).thenReturn(tenantId);
        when(jdbc.queryForObject(anyString(), eq(Integer.class),
            eq(marketplaceId), eq(tenantId), eq(tenantId))).thenReturn(0);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.orders(auth, request, marketplaceId, 50));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(), any(), any());
    }

    @Test
    void eachBusinessReadIsFilteredByServerTenantAndMarketplace() {
        allow();
        controller.listings(auth, request, marketplaceId, 25);
        controller.orders(auth, request, marketplaceId, 25);
        controller.inventory(auth, request, marketplaceId, 25);
        // The literal SQL binds the selected tenant and permitted marketplace
        // even after the session/marketplace access check.
        verify(jdbc, times(3)).query(anyString(), any(RowMapper.class),
            eq(tenantId), eq(marketplaceId), eq(25));
    }

    @Test
    void listMarketplacesDoesNotReadAnyOtherTenant() {
        allow();
        controller.marketplaces(auth, request);
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(tenantId), any(UUID.class));
    }

    @Test
    void cannotRequestUnboundedPages() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.inventory(auth, request, marketplaceId, 10000));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(jdbc, tenants);
    }
}
