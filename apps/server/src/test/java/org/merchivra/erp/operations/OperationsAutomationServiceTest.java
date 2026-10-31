package org.merchivra.erp.operations;

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

class OperationsAutomationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OperationsAutomationService service = new OperationsAutomationService(jdbc, 3);
    private final UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID finding = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void invalidFilterAndStateNeverUseDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.findings(tenant, "INVALID")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.changeState(tenant, actor, finding, "CLEARED")).getStatusCode());
        verifyNoInteractions(jdbc);
        assertThrows(IllegalArgumentException.class, () -> new OperationsAutomationService(jdbc, -1));
        assertThrows(IllegalArgumentException.class, () -> new OperationsAutomationService(jdbc, 10001));
    }

    @Test
    void scansSerializeTenantAndWriteNoSellerDataOrAmazonRequests() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(UUID.class), eq(tenant)))
            .thenReturn(List.of(tenant));
        service.scan(tenant, actor, "MANUAL");
        verify(jdbc).queryForList(contains("FOR UPDATE"), eq(UUID.class), eq(tenant));
        verify(jdbc).update(contains("marketplace_listing"), any(UUID.class), eq(tenant));
        verify(jdbc).update(contains("INVENTORY_UNKNOWN"), any(UUID.class), eq(tenant));
        verify(jdbc).update(contains("'LOW_STOCK'"), any(UUID.class), eq(tenant), eq(3));
        verify(jdbc).update(contains("'REFUND_REVIEW'"), any(UUID.class), eq(tenant));
        verify(jdbc).update(contains("last_scan_id <>"), eq(tenant), any(UUID.class));
        verify(jdbc).update(contains("ops_automation_run"), any(UUID.class),
            eq(tenant), eq(actor), eq("MANUAL"), eq(0), eq(0));
    }

    @Test
    void inactiveTenantCannotBeScanned() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(tenant)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.scan(tenant, actor, "MANUAL")).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void foreignFindingIsInvisibleAndDoesNotMutate() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(finding)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.changeState(tenant, actor, finding, "DONE")).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void clearedFindingCannotBeAcknowledged() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(finding)))
            .thenReturn(List.of("CLEARED"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.changeState(tenant, actor, finding, "OPEN")).getStatusCode());
    }

    @Test
    void repeatedDoneIsIdempotentAndCreatesNoAudit() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(finding)))
            .thenReturn(List.of("DONE"));
        assertEquals("DONE", service.changeState(tenant, actor, finding, "DONE").state());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void genuineTransitionWritesScopedAudit() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenant), eq(finding)))
            .thenReturn(List.of("OPEN"));
        service.changeState(tenant, actor, finding, "ACKNOWLEDGED");
        verify(jdbc).update(contains("UPDATE ops_finding"), eq("ACKNOWLEDGED"),
            eq(tenant), eq(finding));
        verify(jdbc).update(contains("ops_finding_audit"),
            any(UUID.class), eq(tenant), eq(finding), eq(actor),
            eq("OPEN"), eq("ACKNOWLEDGED"));
    }

    @Test
    void readQueriesUseSessionTenantOnly() {
        service.findings(tenant, "ACTIVE");
        service.summary(tenant);
        verify(jdbc).query(contains("WHERE tenant_id = ?"), any(RowMapper.class),
            eq(tenant), eq("ACTIVE"));
        verify(jdbc).queryForObject(contains("ops_finding WHERE tenant_id = ?"),
            any(RowMapper.class), eq(tenant));
    }
}
