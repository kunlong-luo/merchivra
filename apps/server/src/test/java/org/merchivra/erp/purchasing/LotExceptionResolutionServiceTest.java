package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
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

class LotExceptionResolutionServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final LotExceptionResolutionService service=new LotExceptionResolutionService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID proposer=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID reviewer=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID receiver=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID wh=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID sku=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID bin=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID position=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID caseId=UUID.fromString("99999999-9999-4999-8999-999999999999");
    private final UUID transferId=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID receipt=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID resolutionId=UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID key=UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private final UUID operation=UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final String note="Verified physical lot count and receiving documents";
    private void active(){
        when(jdbc.queryForList(contains("FROM tenant"),
            eq(UUID.class),eq(tenant))).thenReturn(List.of(tenant));
    }
    private void warehouse(){
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(wh))).thenReturn(List.of(wh));
    }
    private void balance(long qty){
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(wh),eq(sku))).thenReturn(qty);
    }
    private LotExceptionResolutionService.Resolution snapshot(String type,
        String status,String reviewed,String operationId){
        return new LotExceptionResolutionService.Resolution(
            resolutionId.toString(),type,
            "RELEASED_SURPLUS_BIND".equals(type)?caseId.toString():null,
            "TRANSFER_SHORTAGE".equals(type)?transferId.toString():null,
            "RELEASED_SURPLUS_BIND".equals(type)?receipt.toString():null,
            position.toString(),wh.toString(),sku.toString(),bin.toString(),
            10,20,3,"RCPT-2026-001",note,status,proposer.toString(),
            reviewed,reviewed==null?null:"Independent review confirmed",operationId);
    }
    private void stored(LotExceptionResolutionService.Resolution... results){
        when(jdbc.query(contains("FROM erp_local_lot_exception_resolution WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(resolutionId)))
            .thenReturn(List.of(results[0]),List.of(results[results.length-1]));
    }
    private void countEvidence(String state) throws Exception{
        when(jdbc.query(contains("FROM erp_local_lot_count_review"),
            any(RowMapper.class),eq(tenant),eq(caseId))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("id",UUID.class)).thenReturn(caseId);
            when(rs.getObject("position_id",UUID.class)).thenReturn(position);
            when(rs.getObject("warehouse_id",UUID.class)).thenReturn(wh);
            when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
            when(rs.getObject("expected_bin_id",UUID.class)).thenReturn(bin);
            when(rs.getInt("expected_position_quantity")).thenReturn(10);
            when(rs.getInt("observed_quantity")).thenReturn(13);
            when(rs.getString("status")).thenReturn(state);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    private void transferEvidence(String state) throws Exception{
        when(jdbc.query(contains("FROM erp_local_lot_action a"),
            any(RowMapper.class),eq(tenant),eq(transferId))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("id",UUID.class)).thenReturn(transferId);
            when(rs.getObject("destination_position_id",UUID.class)).thenReturn(position);
            when(rs.getObject("destination_warehouse_id",UUID.class)).thenReturn(wh);
            when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
            when(rs.getObject("destination_bin_id",UUID.class)).thenReturn(bin);
            when(rs.getInt("quantity")).thenReturn(10);
            when(rs.getInt("received_quantity")).thenReturn(7);
            when(rs.getString("status")).thenReturn(state);
            when(rs.getObject("acknowledged_by",UUID.class)).thenReturn(receiver);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    private void position(UUID origin) throws Exception {
        when(jdbc.query(contains("FROM erp_local_lot_position"),
            any(RowMapper.class),eq(tenant),eq(position))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("id",UUID.class)).thenReturn(position);
            when(rs.getObject("receipt_id",UUID.class)).thenReturn(receipt);
            when(rs.getObject("warehouse_id",UUID.class)).thenReturn(wh);
            when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
            when(rs.getObject("bin_id",UUID.class)).thenReturn(bin);
            when(rs.getObject("origin_position_id",UUID.class)).thenReturn(origin);
            when(rs.getInt("placed_quantity")).thenReturn(10);
            when(rs.getInt("remaining_quantity")).thenReturn(10);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    @Test void malformedProposalDoesNotQueryDatabase(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,proposer,
                new LotExceptionResolutionService.Proposal(key,"RELEASED_SURPLUS_BIND",
                    caseId,null,"RCPT-001",note))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,proposer,
                new LotExceptionResolutionService.Proposal(key,"TRANSFER_SHORTAGE",
                    transferId,receipt,"RCPT-001",note))).getStatusCode());
        verifyNoInteractions(jdbc,stock);
    }
    @Test void rejectedV50SurplusCannotBeProposed() throws Exception {
        active();countEvidence("REJECTED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,proposer,
                new LotExceptionResolutionService.Proposal(key,"RELEASED_SURPLUS_BIND",
                    caseId,receipt,"RCPT-2026-001",note))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void originalProposerCannotApproveOwnResolution(){
        active();stored(snapshot("RELEASED_SURPLUS_BIND","PENDING_REVIEW",null,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.decide(tenant,proposer,resolutionId,
                new LotExceptionResolutionService.Decision("APPROVE",
                    "Independent review confirmed"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void receiverCannotApproveDestinationShortage() throws Exception {
        active();stored(snapshot("TRANSFER_SHORTAGE","PENDING_REVIEW",null,null));
        transferEvidence("MISMATCH");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.decide(tenant,receiver,resolutionId,
                new LotExceptionResolutionService.Decision("APPROVE",
                    "Independent review confirmed"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void surplusRequiresUnlocatedReleasedStockAndNeverPostsPositiveLedger()
        throws Exception {
        active();stored(snapshot("RELEASED_SURPLUS_BIND","PENDING_REVIEW",null,null),
            snapshot("RELEASED_SURPLUS_BIND","APPLIED",reviewer.toString(),null));
        countEvidence("EVIDENCE_REQUIRED");warehouse();position(null);balance(20);
        when(jdbc.query(contains("FROM erp_receipt_quality_batch b"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString("status")).thenReturn("RELEASED");
                when(rs.getInt("accepted")).thenReturn(20);
                return List.of(mapper.mapRow(rs,0));
            });
        when(jdbc.queryForObject(contains("origin_position_id IS NULL"),
            eq(Long.class),eq(tenant),eq(receipt))).thenReturn(10L);
        when(jdbc.queryForObject(contains("SUM(remaining_quantity)"),
            eq(Long.class),eq(tenant),eq(wh),eq(sku))).thenReturn(10L);
        when(jdbc.update(contains("SET placed_quantity=placed_quantity+?"),
            eq(3),eq(3),eq(tenant),eq(position),eq(bin),eq(10),eq(10)))
            .thenReturn(1);
        var applied=service.decide(tenant,reviewer,resolutionId,
            new LotExceptionResolutionService.Decision("APPROVE",
                "Independent review confirmed"));
        assertEquals("APPLIED",applied.status());
        assertNull(applied.operationId());
        verify(jdbc).update(contains("SET placed_quantity=placed_quantity+?"),
            eq(3),eq(3),eq(tenant),eq(position),eq(bin),eq(10),eq(10));
        verify(jdbc).update(contains("'APPLIED'"),any(Object[].class)); // checked below
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void surplusCannotUseStockAlreadyPositionedElsewhere() throws Exception {
        active();stored(snapshot("RELEASED_SURPLUS_BIND","PENDING_REVIEW",null,null));
        countEvidence("EVIDENCE_REQUIRED");warehouse();position(null);balance(12);
        when(jdbc.query(contains("FROM erp_receipt_quality_batch b"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString("status")).thenReturn("RELEASED");
                when(rs.getInt("accepted")).thenReturn(20);
                return List.of(mapper.mapRow(rs,0));
            });
        when(jdbc.queryForObject(contains("origin_position_id IS NULL"),
            eq(Long.class),eq(tenant),eq(receipt))).thenReturn(10L);
        when(jdbc.queryForObject(contains("SUM(remaining_quantity)"),
            eq(Long.class),eq(tenant),eq(wh),eq(sku))).thenReturn(11L);
        // Even with signed balance snapshot mismatch, a positive ledger entry is forbidden.
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.decide(tenant,reviewer,resolutionId,
                new LotExceptionResolutionService.Decision("APPROVE",
                    "Independent review confirmed"))).getStatusCode());
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void shortagePostsOneSignedNegativeCountAfterPositionReduction()
        throws Exception {
        active();stored(snapshot("TRANSFER_SHORTAGE","PENDING_REVIEW",null,null),
            snapshot("TRANSFER_SHORTAGE","APPLIED",reviewer.toString(),operation.toString()));
        transferEvidence("MISMATCH");warehouse();position(receipt);balance(20);
        when(jdbc.update(contains("SET remaining_quantity=remaining_quantity-?"),
            eq(3),eq(tenant),eq(position),eq(bin),eq(10))).thenReturn(1);
        var posted=new WarehouseStockOperationService.Operation(operation.toString(),
            "COUNT",wh.toString(),null,sku.toString(),17,20,-3,17,
            "Verified destination shortage",reviewer.toString(),"2026-11-01T00:00:00Z");
        when(stock.post(eq(tenant),eq(reviewer),
            any(WarehouseStockOperationService.Request.class))).thenReturn(posted);
        var applied=service.decide(tenant,reviewer,resolutionId,
            new LotExceptionResolutionService.Decision("APPROVE",
                "Independent review confirmed"));
        assertEquals(operation.toString(),applied.operationId());
        verify(stock).post(eq(tenant),eq(reviewer),argThat(x->
            "COUNT".equals(x.type()) && x.quantity()==17 &&
            x.expectedOnHand()==20 && x.warehouseId().equals(wh)));
        verify(jdbc).update(contains("SET remaining_quantity=remaining_quantity-?"),
            eq(3),eq(tenant),eq(position),eq(bin),eq(10));
    }
}
