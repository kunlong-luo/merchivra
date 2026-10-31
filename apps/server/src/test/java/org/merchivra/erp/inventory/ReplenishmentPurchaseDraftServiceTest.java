package org.merchivra.erp.inventory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.purchasing.PurchasingService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplenishmentPurchaseDraftServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchasingService purchasing=mock(PurchasingService.class);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID marketplace=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID supplier=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID evidence=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID key=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final ReplenishmentPurchaseDraftService service=
        new ReplenishmentPurchaseDraftService(jdbc,purchasing,
            Clock.fixed(Instant.parse("2026-10-31T08:00:00Z"),ZoneOffset.UTC));

    private ReplenishmentPurchaseDraftService.Request request() {
        return new ReplenishmentPurchaseDraftService.Request(key,"SKU-A",supplier,"USD",
            "4.50","2026-10-31T07:00:00Z",evidence,15);
    }

    @Test void malformedRequestsDoNotTouchDatabaseOrCreateOrders() {
        var original=request();
        var invalid=new ReplenishmentPurchaseDraftService.Request(key,"SKU-A",
            supplier,"USD","not-a-price",original.expectedPolicyUpdatedAt(),evidence,15);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.create(tenant,actor,marketplace,invalid)).getStatusCode());
        var noEvidence=new ReplenishmentPurchaseDraftService.Request(key,"SKU-A",
            supplier,"USD","1.00",original.expectedPolicyUpdatedAt(),null,15);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.create(tenant,actor,marketplace,noEvidence)).getStatusCode());
        verifyNoInteractions(jdbc,purchasing);
    }

    @Test void staleOrMissingListingMappingCannotCreateLocalPurchase() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.query(contains("erp_replenishment_purchase_draft_source"),
            any(RowMapper.class),eq(tenant),eq(key))).thenReturn(List.of());
        when(jdbc.query(contains("FROM marketplace_listing"),any(RowMapper.class),
            eq(tenant),eq(marketplace),eq("SKU-A"))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.create(tenant,actor,marketplace,request())).getStatusCode());
        verifyNoInteractions(purchasing);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void secondRequestKeyForSameObservationCannotProduceAnotherOrder() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.query(contains("erp_replenishment_purchase_draft_source"),
            any(RowMapper.class),eq(tenant),eq(key))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("observation_id=?"),eq(Integer.class),
            eq(tenant),eq(marketplace),eq("SKU-A"),eq(evidence))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.create(tenant,actor,marketplace,request())).getStatusCode());
        verifyNoInteractions(purchasing);
    }

    @Test void foreignOrSuspendedTenantCannotAcquireDraftLock() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->service.create(tenant,actor,marketplace,request())).getStatusCode());
        verifyNoInteractions(purchasing);
    }
}
