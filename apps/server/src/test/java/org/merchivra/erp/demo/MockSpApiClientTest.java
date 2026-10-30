package org.merchivra.erp.demo;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MockSpApiClientTest {
    private final MockSpApiClient client = new MockSpApiClient(new MockMarketplaceOverviewGateway());

    @Test
    void usOrdersArePagedAndFinancialsRemainUnobserved() {
        var data = client.simulate("ATVPDKIKX0DER");
        assertEquals(2, data.orderPages().size());
        assertEquals("MOCK-PAGE-1", data.orderPages().getFirst().nextToken());
        assertNull(data.orderPages().getLast().nextToken());
        assertEquals("NOT_AUTHORIZED", data.financeStatus());
        assertEquals("PARTIAL", data.orderPages().getFirst().records().getFirst().refundStatus());
        assertEquals("NOT_OBSERVED", data.orderPages().getFirst().records().getFirst().financialStatus());
        assertNull(data.inventory().get(1).fulfillable());
    }

    @Test
    void euObservedZeroIsNotUnknownAndJapanDoesNotInventSourceData() {
        var eu = client.simulate("A1PA6795UKMFR9");
        assertEquals(1, eu.orderPages().size());
        assertEquals(0, eu.inventory().getFirst().fulfillable());
        assertEquals("OBSERVED", eu.inventory().getFirst().quality());
        assertThrows(IllegalArgumentException.class, () -> client.simulate("A1VC38T7YXB528"));
    }

    @Test
    void identicalInternalSkuHasSameTenantIdentityAcrossMarketplaces() {
        var tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var other = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertEquals(SandboxSyncService.stableProductId(tenant, "SANDBOX-MV-CUP-BLUE"),
            SandboxSyncService.stableProductId(tenant, "SANDBOX-MV-CUP-BLUE"));
        assertNotEquals(SandboxSyncService.stableProductId(tenant, "SANDBOX-MV-CUP-BLUE"),
            SandboxSyncService.stableProductId(other, "SANDBOX-MV-CUP-BLUE"));
    }

    @Test
    void syntheticSourceKeysAreStableAndMarketplaceScoped() {
        var tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var one = UUID.randomUUID();
        var two = UUID.randomUUID();
        assertEquals(SandboxSyncService.stableId(tenant, one, "order", "SANDBOX-MOCK-US-001"),
            SandboxSyncService.stableId(tenant, one, "order", "SANDBOX-MOCK-US-001"));
        assertNotEquals(SandboxSyncService.stableId(tenant, one, "order", "SANDBOX-MOCK-US-001"),
            SandboxSyncService.stableId(tenant, two, "order", "SANDBOX-MOCK-US-001"));
    }
}
