package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.merchivra.erp.demo.MockSpApiClient;
import org.merchivra.erp.spapi.SpApiClient;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No production network or real Seller credentials are used. */
class OrdersSyncModesTest {
    private final MockSpApiClient mock=mock(MockSpApiClient.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ObjectProvider<SpApiSellerGrantVault> vaultProvider=mock(ObjectProvider.class);
    private final ObjectProvider<SpApiClient> clientProvider=mock(ObjectProvider.class);
    private final OrdersSyncConfiguration config=new OrdersSyncConfiguration();

    private OrdersSyncSource source(String mode,boolean approval,boolean demo,
        boolean spapi,boolean sandbox,boolean writes,boolean oauth,boolean verification) {
        return config.ordersSyncSource(mock,jdbc,vaultProvider,clientProvider,
            mode,approval,demo,spapi,sandbox,writes,oauth,verification);
    }

    @Test void scheduledJobsNeverCrossMockAndLiveModes() {
        var src=mock(OrdersSyncSource.class);
        when(src.dataOrigin()).thenReturn("LIVE");
        var worker=new OrdersSyncService(jdbc,
            mock(org.springframework.transaction.support.TransactionTemplate.class),src);
        worker.due(5);
        verify(jdbc).query(contains("AND mode=?"),any(org.springframework.jdbc.core.RowMapper.class),
            eq("LIVE"),eq(5));
    }

    @Test void mockNeverCoexistsWithLiveEgressOrLiveApproval() {
        assertEquals("MOCK",source("MOCK",false,true,false,true,false,false,false).dataOrigin());
        assertThrows(IllegalStateException.class,()->
            source("MOCK",false,true,true,true,false,false,false));
        assertThrows(IllegalStateException.class,()->
            source("MOCK",true,true,false,true,false,false,false));
    }

    @Test void liveRequiresAllExplicitIndependentGuards() {
        assertThrows(IllegalStateException.class,()->
            source("LIVE",false,false,true,false,false,true,true));
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,true,true,false,false,true,true));
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,false,false,false,false,true,true));
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,false,true,true,false,true,true));
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,false,true,false,true,true,true));
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,false,true,false,false,false,true));
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,false,true,false,false,true,false));
        assertThrows(IllegalStateException.class,()->
            source("auto",true,false,true,false,false,true,true));
        verifyNoInteractions(vaultProvider,clientProvider);
    }

    @Test void missingVaultNeverCreatesLiveSource() {
        assertThrows(IllegalStateException.class,()->
            source("LIVE",true,false,true,false,false,true,true));
    }

    @Test void enabledLiveSourceStillChecksRoleForEveryPage() throws Exception {
        SpApiSellerGrantVault vault=mock(SpApiSellerGrantVault.class);
        SpApiClient client=mock(SpApiClient.class);
        when(vaultProvider.getIfAvailable()).thenReturn(vault);
        when(clientProvider.getIfAvailable()).thenReturn(client);
        OrdersSyncSource live=source("LIVE",true,false,true,false,false,true,true);
        assertEquals("LIVE",live.dataOrigin());
        var scope=new OrdersSyncSource.Scope(UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER");
        assertThrows(SecurityException.class,()->live.requireAuthorized(scope));
        assertThrows(SecurityException.class,()->live.fetch(scope,
            Instant.parse("2026-10-30T00:00:00Z"),
            Instant.parse("2026-10-31T00:00:00Z"),null));
        verifyNoInteractions(client);
    }

    @Test void liveAdapterRejectsScopeMismatchBeforeAnyRead() throws Exception {
        ApprovedOrdersReadGateway gateway=mock(ApprovedOrdersReadGateway.class);
        var live=new LiveOrdersSyncSource(gateway);
        var scope=new OrdersSyncSource.Scope(UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER");
        doThrow(new SecurityException("revoked")).when(gateway)
            .requireAuthorized(eq(scope.tenant()),eq(scope.marketplace()),eq(scope.marketplaceCode()));
        assertThrows(SecurityException.class,()->live.fetch(scope,Instant.EPOCH,
            Instant.parse("2026-10-30T00:00:00Z"),null));
        verify(gateway,never()).search(any(),any(),any(),any(),any());
    }
}
