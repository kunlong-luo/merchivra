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

class SupplierPerformanceServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SupplierPerformanceService service=new SupplierPerformanceService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test void scoreCannotBeInventedFromMissingOrSmallSamples() {
        assertNull(SupplierPerformanceService.score(0,0,0,0,0));
        assertNull(SupplierPerformanceService.score(2,2,3,30,30));
        assertNull(SupplierPerformanceService.score(3,3,2,30,30));
        assertNull(SupplierPerformanceService.score(3,3,3,31,30));
        assertNull(SupplierPerformanceService.ratio(0,0));
    }
    @Test void explicitRatioAndProvisionalWeightedEvidenceAreDeterministic() {
        assertEquals("75.0",SupplierPerformanceService.ratio(3,4));
        assertEquals("80.0",SupplierPerformanceService.ratio(80,100));
        assertEquals("72.0",SupplierPerformanceService.score(3,2,3,80,100));
        assertEquals("100.0",SupplierPerformanceService.score(3,3,3,30,30));
        assertEquals("0.0",SupplierPerformanceService.score(3,0,3,0,30));
    }
    @Test void scorecardsNeverUseAnotherTenantOrForeignUrls() {
        when(jdbc.query(contains("FROM erp_supplier s"),any(RowMapper.class),
            eq(tenant),eq(tenant),eq(tenant),eq(tenant),eq(201))).thenReturn(List.of());
        assertTrue(service.scorecards(tenant).isEmpty());
        verify(jdbc).query(contains("WHERE s.tenant_id=? AND s.status='ACTIVE'"),
            any(RowMapper.class),eq(tenant),eq(tenant),eq(tenant),eq(tenant),eq(201));
    }
}
