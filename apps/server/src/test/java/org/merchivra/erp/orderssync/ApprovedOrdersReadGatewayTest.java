package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiPayloadDecoder;
import org.merchivra.erp.spapi.SpApiReadOperations;
import org.merchivra.erp.spapi.SpApiResponse;
import org.merchivra.erp.spapi.SpApiRegion;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovedOrdersReadGatewayTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SpApiSellerGrantVault vault=mock(SpApiSellerGrantVault.class);
    private final SpApiReadOperations api=mock(SpApiReadOperations.class);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID marketplace=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID account=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String CODE="ATVPDKIKX0DER";
    private static final Instant AFTER=Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant BEFORE=Instant.parse("2026-10-21T00:00:00Z");
    private final ApprovedOrdersReadGateway gateway=
        new ApprovedOrdersReadGateway(jdbc,vault,api,new SpApiPayloadDecoder());

    @Test void deniesMissingAccountWithoutNetwork() throws Exception {
        assertThrows(SecurityException.class,
            ()->gateway.search(tenant,marketplace,AFTER,BEFORE,null));
        verifyNoInteractions(vault,api);
    }

    @Test void refusesSellerWithoutIndependentOrdersRoleBeforeNetwork() throws Exception {
        mockMarketplace();
        assertThrows(SecurityException.class,
            ()->gateway.search(tenant,marketplace,AFTER,BEFORE,null));
        verify(vault).loadOrdersApproved(tenant,account,CODE);
        verifyNoInteractions(api);
    }

    @Test void safeV2026PageWithVerifiedRoleAndScope() throws Exception {
        mockMarketplace();
        var approved=approvedScope();
        when(vault.loadOrdersApproved(tenant,account,CODE)).thenReturn(Optional.of(approved));
        when(api.searchOrders(eq(approved),eq(AFTER),eq(BEFORE),isNull()))
            .thenReturn(new SpApiResponse(200,"""
                {"orders":[{"orderId":"ORDER-1",
                  "lastUpdatedTime":"2026-10-20T10:00:00Z",
                  "salesChannel":{"marketplaceId":"ATVPDKIKX0DER"},
                  "fulfillment":{"status":"SHIPPED"}}],
                 "pagination":{"nextToken":"OPAQUE-CURSOR"}}""",java.util.Map.of()));
        var result=gateway.search(tenant,marketplace,AFTER,BEFORE,null);
        assertEquals("OPAQUE-CURSOR",result.nextToken());
        assertEquals("ORDER-1",result.orders().getFirst().orderId());
        assertEquals(CODE,result.orders().getFirst().marketplaceCode());
        assertEquals("SHIPPED",result.orders().getFirst().fulfillment());
        verify(vault,times(2)).loadOrdersApproved(tenant,account,CODE);
    }

    @Test void mismatchedMarketplaceNeverEscapesDecoder() throws Exception {
        mockMarketplace();
        var approved=approvedScope();
        when(vault.loadOrdersApproved(tenant,account,CODE)).thenReturn(Optional.of(approved));
        when(api.searchOrders(any(),any(),any(),any()))
            .thenReturn(new SpApiResponse(200,"""
                {"orders":[{"orderId":"ORDER-1",
                   "lastUpdatedTime":"2026-10-20T10:00:00Z",
                   "salesChannel":{"marketplaceId":"A1PA6795UKMFR9"}}]}""",
                java.util.Map.of()));
        assertThrows(IllegalArgumentException.class,
            ()->gateway.search(tenant,marketplace,AFTER,BEFORE,"NEXT"));
    }

    @Test void revokedGrantDuringFetchDropsResults() throws Exception {
        mockMarketplace();
        var approved=approvedScope();
        when(vault.loadOrdersApproved(tenant,account,CODE))
            .thenReturn(Optional.of(approved),Optional.empty());
        when(api.searchOrders(any(),any(),any(),isNull()))
            .thenReturn(new SpApiResponse(200,"{\"orders\":[]}",java.util.Map.of()));
        assertThrows(SecurityException.class,
            ()->gateway.search(tenant,marketplace,AFTER,BEFORE,null));
    }

    @Test void unsafeCursorAndWindowFailBeforeAnyDatabaseAccess() throws Exception {
        assertThrows(IllegalArgumentException.class,
            ()->gateway.search(tenant,marketplace,BEFORE,AFTER,null));
        assertThrows(IllegalArgumentException.class,
            ()->gateway.search(tenant,marketplace,AFTER,BEFORE,""));
        assertThrows(IllegalArgumentException.class,
            ()->gateway.search(tenant,marketplace,AFTER,BEFORE,"x".repeat(4097)));
        verifyNoInteractions(jdbc,vault,api);
    }

    @SuppressWarnings("unchecked")
    private void mockMarketplace() {
        when(jdbc.query(contains("FROM seller_marketplace"),any(RowMapper.class),
            eq(tenant),eq(marketplace))).thenAnswer(invocation -> {
                RowMapper<?> mapper=invocation.getArgument(1);
                java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
                when(rs.getObject(1,UUID.class)).thenReturn(account);
                when(rs.getString(2)).thenReturn(CODE);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private SpApiSellerScope approvedScope() {
        return new SpApiSellerScope(tenant,account,"A2TESTSELLER1",
            CODE,SpApiRegion.NA,"synthetic-not-live-token",
            Set.of("Inventory and Order Tracking"));
    }
}
