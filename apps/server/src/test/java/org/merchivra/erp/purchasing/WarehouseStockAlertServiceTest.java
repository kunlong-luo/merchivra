package org.merchivra.erp.purchasing;

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

class WarehouseStockAlertServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockAlertService service=new WarehouseStockAlertService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID sku=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID finding=UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test void unobservedLedgerNeverBecomesZeroStock() {
        assertEquals("NO_LEDGER",WarehouseStockAlertService.state(null,5));
        assertNull(WarehouseStockAlertService.suggested(null,5,20));
        assertEquals("LEDGER_ANOMALY",WarehouseStockAlertService.state(-1L,5));
        assertNull(WarehouseStockAlertService.suggested(-1L,5,20));
        assertEquals("LOW_STOCK",WarehouseStockAlertService.state(0L,5));
        assertEquals(20L,WarehouseStockAlertService.suggested(0L,5,20));
        assertEquals(15L,WarehouseStockAlertService.suggested(5L,5,20));
        assertEquals("HEALTHY",WarehouseStockAlertService.state(6L,5));
        assertNull(WarehouseStockAlertService.suggested(6L,5,20));
    }
    @Test void invalidPoliciesAndQueueStatesFailBeforeTouchingDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.setPolicy(tenant,actor,warehouse,sku,10,10)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.setPolicy(tenant,actor,warehouse,sku,-1,20)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.setPolicy(tenant,actor,warehouse,sku,1,10_000_001)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.findings(tenant,warehouse,"UNKNOWN")).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void suspendedTenantCannotWriteScanOrClearFindings() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.scan(tenant,actor,"MANUAL")).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void scanAllLocalRulesWithOneTenantScopedTransaction() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        var result=service.scan(tenant,actor,"MANUAL");
        assertEquals("MANUAL",result.triggerType());
        verify(jdbc).update(contains("'LOCAL_LOW_STOCK'"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("'LOCAL_LEDGER_NEGATIVE'"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("'LOCAL_COUNT_VARIANCE'"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("UPDATE erp_local_stock_finding SET state='CLEARED'"),
            eq(tenant),any(UUID.class));
        verify(jdbc).update(contains("INSERT INTO erp_local_stock_scan_run"),
            any(UUID.class),eq(tenant),eq(actor),eq("MANUAL"),eq(0),eq(0));
    }
    @Test void foreignFindingCannotBeChanged() {
        when(jdbc.query(contains("FROM erp_local_stock_finding"),
            any(RowMapper.class),eq(tenant),eq(finding))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,"DONE")).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void clearedFindingCannotBeReopened() {
        when(jdbc.query(contains("FROM erp_local_stock_finding"),
            any(RowMapper.class),eq(tenant),eq(finding))).thenReturn(List.of("CLEARED"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,"OPEN")).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
