package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.demo.DemoModels;
import org.merchivra.erp.demo.MockSpApiClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrdersSyncServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID marketplace=UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test void disabledSourceCannotStartOrRunAndNeverQueriesDb() {
        OrdersSyncSource disabled = new OrdersSyncSource() {
            public boolean allowed(){ return false; }
            public String dataOrigin(){ return "MOCK"; }
            public void requireAuthorized(Scope scope){ throw new SecurityException(); }
            public Page fetch(Scope scope,Instant after,Instant before,String cursor) {
                throw new AssertionError("Should never call source");
            }
        };
        var service=new OrdersSyncService(jdbc,tx,disabled);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.start(tenant,marketplace)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.step(tenant,marketplace)).getStatusCode());
        verifyNoInteractions(jdbc,tx);
    }

    @Test void foreignMarketplaceIsNeverReadFromSource() throws Exception {
        OrdersSyncSource source=mock(OrdersSyncSource.class);
        when(source.allowed()).thenReturn(true);
        var service=new OrdersSyncService(jdbc,tx,source);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
            eq(tenant),eq(marketplace))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.status(tenant,marketplace)).getStatusCode());
        verify(source,never()).fetch(any(),any(),any(),any());
    }

    @Test void mockSourceEnforcesLocalSiteAndDoesNotRequireRemoteCredentials() throws Exception {
        MockSpApiClient mock=mock(MockSpApiClient.class);
        var order=new DemoModels.Order(
            "mock-record-id","shop","TEST-ORDER-1","SKU-1","UNSHIPPED",
            "NONE","NOT_OBSERVED",new DemoModels.Money("1.00","USD"),
            new DemoModels.Money("0.00","USD"),"2026-10-30T12:00:00Z");
        when(mock.simulate("ATVPDKIKX0DER")).thenReturn(new MockSpApiClient.Snapshot(
            List.of(),
            List.of(new MockSpApiClient.Page(List.of(order),"MOCK-PAGE-1"),
                    new MockSpApiClient.Page(List.of(order),null)),
            List.of(),"NOT_AUTHORIZED"));
        var source=new MockOrdersSyncSource(mock,true);
        var scope=new OrdersSyncSource.Scope(tenant,marketplace,"ATVPDKIKX0DER");
        var first=source.fetch(scope,Instant.parse("2026-10-29T00:00:00Z"),
            Instant.parse("2026-10-31T00:00:00Z"),null);
        assertEquals("TEST-ORDER-1",first.orders().getFirst().orderId());
        assertEquals("MOCK-ORDERS-PAGE-1",first.nextToken());
        assertNull(source.fetch(scope,Instant.EPOCH,Instant.now(),first.nextToken()).nextToken());
        assertThrows(IllegalArgumentException.class,()->source.fetch(scope,
            Instant.EPOCH,Instant.now(),"token-from-another-seller"));
        assertThrows(SecurityException.class,()->source.requireAuthorized(
            new OrdersSyncSource.Scope(tenant,marketplace,"A1VC38T7YXB528")));
        assertFalse(new MockOrdersSyncSource(mock,false).allowed());
    }
}
