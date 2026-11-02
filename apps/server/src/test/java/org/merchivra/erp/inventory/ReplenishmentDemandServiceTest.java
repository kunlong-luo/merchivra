package org.merchivra.erp.inventory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class ReplenishmentDemandServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ReplenishmentDemandService service=new ReplenishmentDemandService(
        jdbc,Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"),ZoneOffset.UTC));
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID marketplace=UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test void noCoverageOrUnmappedNeverBecomesZeroDemand() {
        var missing=ReplenishmentDemandService.interpret(true,false,"LIVE",0,0,0,0,0,0,14);
        assertEquals("INCOMPLETE_COVERAGE",missing.status());
        assertNull(missing.dailyUnits());
        assertNull(missing.units28d());
        var wrongSku=ReplenishmentDemandService.interpret(false,true,"MOCK",12,0,0,0,8,30,14);
        assertEquals("UNMAPPED",wrongSku.status());
        assertNull(wrongSku.leadTimeUnits());
        var fake=ReplenishmentDemandService.interpret(true,true,"UNVERIFIED",12,0,0,0,8,30,14);
        assertEquals("INCOMPLETE_COVERAGE",fake.status());
    }

    @Test void sourceOrItemCompletenessFailureCannotProducePace() {
        assertEquals("INCOMPLETE_ITEMS",ReplenishmentDemandService.interpret(
            true,true,"LIVE",15,1,0,0,2,45,14).status());
        assertEquals("INCOMPLETE_ITEMS",ReplenishmentDemandService.interpret(
            true,true,"MOCK",15,0,1,0,2,45,14).status());
        assertEquals("INCOMPLETE_ITEMS",ReplenishmentDemandService.interpret(
            true,true,"MOCK",15,0,0,1,2,45,14).status());
        assertEquals("INCOMPLETE_ITEMS",ReplenishmentDemandService.interpret(
            true,true,"LIVE",15,0,0,0,46,45,14).status());
    }

    @Test void explicitZeroOrdersDifferFromMissingCoverage() {
        var result=ReplenishmentDemandService.interpret(true,true,"LIVE",0,0,0,0,0,0,30);
        assertEquals("NO_OBSERVED_ORDERS",result.status());
        assertEquals(0L,result.units28d());
        assertNull(result.dailyUnits());
        assertNull(result.leadTimeUnits());
    }

    @Test void sampleAndLiveOrdersReturnOnlyIllustrativeUnits() {
        var mock=ReplenishmentDemandService.interpret(true,true,"MOCK",10,0,0,0,7,56,14);
        assertEquals("MOCK_SAMPLE",mock.status());
        assertEquals("2.000",mock.dailyUnits());
        assertEquals(28L,mock.leadTimeUnits());
        var observed=ReplenishmentDemandService.interpret(true,true,"LIVE",10,0,0,0,7,33,8);
        assertEquals("OBSERVED_ONLY",observed.status());
        assertEquals("1.179",observed.dailyUnits());
        assertEquals(10L,observed.leadTimeUnits());
        var excessive=ReplenishmentDemandService.interpret(true,true,"LIVE",
            200,0,0,0,200_000,2_000_000,365);
        assertNull(excessive.leadTimeUnits());
    }

    @Test void unknownMarketplaceIsNotReadFromAnyOtherTenant() {
        when(jdbc.queryForObject(contains("seller_marketplace"),eq(Integer.class),
            eq(tenant),eq(marketplace))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.signals(tenant,marketplace)).getStatusCode());
        verify(jdbc,never()).query(contains("erp_replenishment_policy"),
            any(RowMapper.class),any(Object[].class));
    }

    @Test void bulkReadAlwaysScopesTenantAndMarketplace() {
        when(jdbc.queryForObject(contains("seller_marketplace"),eq(Integer.class),
            eq(tenant),eq(marketplace))).thenReturn(1);
        when(jdbc.query(contains("FROM erp_replenishment_policy"),
            any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertEquals(List.of(),service.signals(tenant,marketplace));
        verify(jdbc).query(contains("FROM erp_replenishment_policy"),
            any(RowMapper.class),any(),any(),any(),eq(tenant),eq(marketplace));
    }
}
