package org.merchivra.erp.catalog;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalSellerSetupServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LocalSellerSetupService service = new LocalSellerSetupService(jdbc);
    private final UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID account = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final String key = "request-id-123";

    @Test
    void invalidNamesRegionsAndIdempotencyKeysFailBeforeDatabaseUse() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.createAccount(tenant, actor, " ", "NA", key)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.createAccount(tenant, actor, "Local", "invalid", key)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.createAccount(tenant, actor, "Local", "NA", "bad!")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> LocalSellerSetupService.validateMarketplaceRegion("NA", "A1PA6795UKMFR9"))
                .getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void accountReplayReturnsExistingWithoutDuplicateAudit() {
        var saved = new LocalSellerSetupService.LocalAccount(
            account.toString(), "Local Store", "NA", "UNCONFIGURED");
        // INSERT conflicts with same idempotency key, so no new audit.
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(tenant), eq(key)))
            .thenReturn(saved);
        assertEquals(saved, service.createAccount(tenant, actor, " Local Store ", "NA", key));
        verify(jdbc).update(contains("ON CONFLICT"), any(UUID.class), eq(tenant),
            anyString(), eq("NA"), eq("Local Store"), eq(key));
    }

    @Test
    void changedAccountPayloadCannotReuseIdempotencyKey() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(tenant), eq(key)))
            .thenReturn(new LocalSellerSetupService.LocalAccount(
                account.toString(), "Existing", "EU", "UNCONFIGURED"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.createAccount(tenant, actor, "Different", "NA", key)).getStatusCode());
        verify(jdbc, times(1)).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownOrForeignLocalAccountIsNeverModified() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(account)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.addMarketplace(tenant, actor, account, "ATVPDKIKX0DER"))
            .getStatusCode());
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void wrongRegionMarketplaceIsRejectedWithoutWrite() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(account)))
            .thenReturn(List.of("EU"));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.addMarketplace(tenant, actor, account, "ATVPDKIKX0DER"))
            .getStatusCode());
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatedMarketplaceInsertionDoesNotAppendAnotherAuditEvent() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(account)))
            .thenReturn(List.of("NA"));
        var saved = new LocalSellerSetupService.LocalMarketplace(
            UUID.randomUUID().toString(), account.toString(), "ATVPDKIKX0DER",
            "USD", "America/Los_Angeles", "UNCONFIGURED");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class),
            eq(tenant), eq(account), eq("ATVPDKIKX0DER"))).thenReturn(saved);
        assertEquals(saved, service.addMarketplace(tenant, actor, account, "ATVPDKIKX0DER"));
        verify(jdbc, times(1)).update(anyString(), any(), any(), any(), any(), any(), any());
    }
}
