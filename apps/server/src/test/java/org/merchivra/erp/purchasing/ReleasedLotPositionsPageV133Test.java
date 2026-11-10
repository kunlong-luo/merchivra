package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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

/** V133: source position selection and exact putaway truth beyond 300 rows. */
class ReleasedLotPositionsPageV133Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final ReleasedLotStockService service=new ReleasedLotStockService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID other=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID receipt=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID bin=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final OffsetDateTime timestamp=OffsetDateTime.parse("2026-11-10T12:00:00Z");

    private ReleasedLotStockService.Position position(int n) {
        return new ReleasedLotStockService.Position(new UUID(0,n).toString(),
            receipt.toString(),warehouse.toString(),sku.toString(),bin.toString(),
            "BIN-01","LOT-01",1,1,null,
            timestamp.minusSeconds(n).toInstant().toString());
    }
    @SuppressWarnings("unchecked")
    private void pageFixture(){
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String query=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(query.contains("AND p.warehouse_id=?"));
                assertTrue(query.contains("ORDER BY p.created_at DESC,p.id DESC LIMIT ?"));
                assertFalse(query.contains("LIMIT 300"));
                assertEquals(tenant,args[0]);
                assertEquals(warehouse,args[1]);
                assertEquals(51,args[args.length-1]);
                int prior=0;
                if(args.length==5){
                    UUID last=(UUID)args[3];
                    prior=(int)last.getLeastSignificantBits();
                    assertEquals(timestamp.minusSeconds(prior),
                        ((OffsetDateTime)args[2]).withOffsetSameInstant(ZoneOffset.UTC));
                    assertTrue(query.contains("(p.created_at,p.id) < (?,?)"));
                }else assertEquals(3,args.length);
                var rows=new ArrayList<ReleasedLotStockService.Position>();
                for(int n=prior+1;n<=Math.min(325,prior+51);n++)rows.add(position(n));
                return rows;
            });
    }
    @Test void all325PositionsAcrossSevenPagesWithoutDuplicateOrFalseTotal(){
        pageFixture();
        var found=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        String cursor=null;
        for(int i=0;i<7;i++){
            var page=service.positionsPage(tenant,warehouse,50,cursor);
            sizes.add(page.items().size());
            assertEquals(i<6,page.hasMore());
            for(var item:page.items())assertTrue(found.add(item.id()));
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,50,50,25),sizes);
        assertEquals(325,found.size());
        assertNull(cursor);
        verifyNoInteractions(stock);
    }
    private static void bad(Runnable run){
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,run::run).getStatusCode());
    }
    @Test void cursorCannotCrossTenantWarehouseOrContainInvalidTime(){
        String cursor=LocalKeysetPage.encode(tenant.toString(),warehouse.toString(),
            timestamp.toInstant().toString(),new UUID(0,1).toString());
        bad(()->service.positionsPage(other,warehouse,50,cursor));
        bad(()->service.positionsPage(tenant,other,50,cursor));
        bad(()->service.positionsPage(tenant,warehouse,0,null));
        bad(()->service.positionsPage(tenant,warehouse,101,null));
        bad(()->service.positionsPage(tenant,warehouse,50,"invalid%"));
        bad(()->service.positionsPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),"invalid",new UUID(0,1).toString())));
        bad(()->service.positionsPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),timestamp.toInstant().toString(),"bad-id")));
        verifyNoInteractions(jdbc,stock);
    }
    @Test void receiptAllocationAggregatesAllRootsWithoutCountFromFirstPage() throws Exception {
        when(jdbc.query(contains("SUM(p.placed_quantity)"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenAnswer(inv->{
            String query=inv.getArgument(0);
            assertTrue(query.contains("p.origin_position_id IS NULL"));
            assertFalse(query.contains("LIMIT 300"));
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("receipt_id",UUID.class)).thenReturn(receipt);
            when(rs.getObject("warehouse_id",UUID.class)).thenReturn(warehouse);
            when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
            when(rs.getString("status")).thenReturn("RELEASED");
            when(rs.getInt("accepted")).thenReturn(650);
            when(rs.getLong("placed")).thenReturn(601L);
            return List.of(mapper.mapRow(rs,0));
        });
        var result=service.receiptAllocation(tenant,receipt);
        assertEquals(650,result.acceptedQuantity());
        assertEquals(601L,result.placedQuantity());
        assertEquals(49L,result.remainingQuantity());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void missingReceiptIs404AndNullReceiptRejectsBeforeSql(){
        bad(()->service.receiptAllocation(tenant,null));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.receiptAllocation(tenant,receipt)).getStatusCode());
        verifyNoInteractions(stock);
    }
}
