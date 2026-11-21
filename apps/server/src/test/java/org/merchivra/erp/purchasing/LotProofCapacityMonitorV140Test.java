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

class LotProofCapacityMonitorV140Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotProofGovernanceService governance=mock(LotProofGovernanceService.class);
    private final LotProofCapacityMonitorService service=
        new LotProofCapacityMonitorService(jdbc,governance);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String NOTE="Tenant Owner approved payload byte warning configuration";

    private LotProofCapacityMonitorService.Change set(Long bytes){
        return new LotProofCapacityMonitorService.Change("SET",bytes,NOTE);
    }
    private LotProofCapacityMonitorService.Change disable(){
        return new LotProofCapacityMonitorService.Change("DISABLE",null,NOTE);
    }
    private void disallow(Runnable task,HttpStatus code){
        assertEquals(code,assertThrows(ResponseStatusException.class,
            task::run).getStatusCode());
    }
    @Test void noDefaultQuotaAndExactAtThresholdIsWarning(){
        assertEquals("UNCONFIGURED",LotProofCapacityMonitorService.status(0,null));
        assertEquals("NORMAL",LotProofCapacityMonitorService.status(1048575,1048576L));
        assertEquals("WARNING",LotProofCapacityMonitorService.status(1048576,1048576L));
        assertEquals("WARNING",LotProofCapacityMonitorService.status(1050000,1048576L));
    }
    @Test void rejectsMalformedOrDestructiveCommandsBeforeDatabaseAccess(){
        disallow(()->service.configure(tenant,null,set(1048576L)),HttpStatus.BAD_REQUEST);
        disallow(()->service.configure(tenant,actor,set(null)),HttpStatus.BAD_REQUEST);
        disallow(()->service.configure(tenant,actor,set(0L)),HttpStatus.BAD_REQUEST);
        disallow(()->service.configure(tenant,actor,set(1099511627777L)),HttpStatus.BAD_REQUEST);
        disallow(()->service.configure(tenant,actor,
            new LotProofCapacityMonitorService.Change("DISABLE",1048576L,NOTE)),
            HttpStatus.BAD_REQUEST);
        disallow(()->service.configure(tenant,actor,
            new LotProofCapacityMonitorService.Change("DELETE_ORIGINAL",null,NOTE)),
            HttpStatus.BAD_REQUEST);
        disallow(()->service.configure(tenant,actor,
            new LotProofCapacityMonitorService.Change("SET",1048576L,"short")),
            HttpStatus.BAD_REQUEST);
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    @Test void tenantSpecificThresholdReachesWarningAndAuditsWithoutDeleting() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.query(contains("FROM erp_lot_proof_file_capacity_threshold"),
            any(RowMapper.class),eq(tenant))).thenReturn(List.of(),List.of(1048576L));
        when(governance.capacity(tenant)).thenReturn(
            new LotProofGovernanceService.Capacity(2,2097153,0,2,0,0));
        when(jdbc.update(contains("INSERT INTO erp_lot_proof_file_capacity_threshold\n"),
            any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO erp_lot_proof_file_capacity_threshold_audit"),
            any(Object[].class))).thenReturn(1);
        var result=service.configure(tenant,actor,set(1048576L));
        assertEquals("WARNING",result.status());
        assertEquals(2,result.fileCount());
        assertEquals(2097153,result.originalBytes());
        assertTrue(result.advisoryOnly());
        verify(jdbc).update(contains("INSERT INTO erp_lot_proof_file_capacity_threshold_audit"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("erp_local_lot_disposition_proof_file"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @SuppressWarnings("unchecked")
    @Test void missingAuditRefusesToConfirmCapacityChange(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.query(contains("FROM erp_lot_proof_file_capacity_threshold"),
            any(RowMapper.class),eq(tenant))).thenReturn(List.of());
        when(jdbc.update(contains("INSERT INTO erp_lot_proof_file_capacity_threshold\n"),
            any(Object[].class))).thenReturn(1);
        disallow(()->service.configure(tenant,actor,set(1048576L)),HttpStatus.CONFLICT);
        verify(jdbc).update(contains("INSERT INTO erp_lot_proof_file_capacity_threshold_audit"),
            any(Object[].class));
    }
    @SuppressWarnings("unchecked")
    @Test void sameThresholdIsNotDoubleAudited(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.query(contains("FROM erp_lot_proof_file_capacity_threshold"),
            any(RowMapper.class),eq(tenant))).thenReturn(List.of(1048576L));
        disallow(()->service.configure(tenant,actor,set(1048576L)),HttpStatus.CONFLICT);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
