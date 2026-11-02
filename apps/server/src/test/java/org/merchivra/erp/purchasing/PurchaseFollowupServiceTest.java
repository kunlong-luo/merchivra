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

class PurchaseFollowupServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseFollowupService service=new PurchaseFollowupService(jdbc,7);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID finding=UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test void invalidSettingsFailFast() {
        assertThrows(IllegalArgumentException.class,()->new PurchaseFollowupService(jdbc,0));
        assertThrows(IllegalArgumentException.class,()->new PurchaseFollowupService(jdbc,61));
    }
    @Test void revokedTenantCannotCreateFollowupAndInvalidTriggerFailsClosed() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.scan(tenant,actor,"MANUAL")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.scan(tenant,actor,"HACK")).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void ownerScanKeepsSeparateCoverageAndUnshippedCriteriaAndClearsOld() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.update(contains("'ASN_COVERAGE_GAP'"),any(),eq(tenant),eq(7)))
            .thenReturn(2);
        when(jdbc.update(contains("'ASN_NOT_DISPATCHED'"),any(),eq(tenant),eq(7)))
            .thenReturn(1);
        when(jdbc.update(contains("SET state='CLEARED'"),eq(tenant),any()))
            .thenReturn(1);
        var result=service.scan(tenant,actor,"MANUAL");
        assertEquals("MANUAL",result.triggerType());
        assertEquals(3,result.matchedCount());
        assertEquals(1,result.clearedCount());
        verify(jdbc).update(contains("erp_purchase_followup_scan"),
            any(),eq(tenant),eq(actor),eq("MANUAL"),eq(3),eq(1));
    }
    @Test void invalidOrClearedFindingCannotBeReopened() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,"UNKNOWN")).getStatusCode());
        when(jdbc.query(contains("erp_purchase_followup_finding"),
            any(RowMapper.class),eq(tenant),eq(finding))).thenReturn(List.of("CLEARED"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,"OPEN")).getStatusCode());
    }
}
