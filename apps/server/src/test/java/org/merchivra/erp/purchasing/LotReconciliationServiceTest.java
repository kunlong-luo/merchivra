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

class LotReconciliationServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final LotReconciliationService service=new LotReconciliationService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID observer=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID reviewer=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID wh=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID bin=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID position=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID observation=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID caseId=UUID.fromString("99999999-9999-4999-8999-999999999999");
    private final UUID key=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID operation=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID transfer=UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID quarantine=UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private final UUID dispositionId=UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

    private void active(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void warehouse(){
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(wh))).thenReturn(List.of(wh));
    }
    private void stockBalance(long n){
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(wh),eq(sku))).thenReturn(n);
    }
    private void observation(int counted) throws Exception {
        when(jdbc.query(contains("FROM erp_local_lot_action"),
            any(RowMapper.class),eq(tenant),eq(observation))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("id",UUID.class)).thenReturn(observation);
                when(rs.getObject("source_position_id",UUID.class)).thenReturn(position);
                when(rs.getObject("source_warehouse_id",UUID.class)).thenReturn(wh);
                when(rs.getObject("source_bin_id",UUID.class)).thenReturn(bin);
                when(rs.getInt("expected_remaining")).thenReturn(10);
                when(rs.getInt("observed_quantity")).thenReturn(counted);
                when(rs.getObject("actor_user_id",UUID.class)).thenReturn(observer);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private void position() throws Exception {
        when(jdbc.query(contains("FROM erp_local_lot_position WHERE tenant_id=? AND id=? FOR UPDATE"),
            any(RowMapper.class),eq(tenant),eq(position))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("warehouse_id",UUID.class)).thenReturn(wh);
                when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
                when(rs.getObject("bin_id",UUID.class)).thenReturn(bin);
                when(rs.getInt("remaining_quantity")).thenReturn(10);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private LotReconciliationService.CountCase count(int observed,String status,
        String reviewerId,String operationId){
        return new LotReconciliationService.CountCase(caseId.toString(),
            observation.toString(),position.toString(),wh.toString(),sku.toString(),
            bin.toString(),10,observed,20,"Physical cartons were recounted",
            status,observer.toString(),reviewerId,operationId,
            reviewerId==null?null:"Independent recount completed");
    }
    private void countStored(LotReconciliationService.CountCase... states){
        when(jdbc.query(contains("FROM erp_local_lot_count_review WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(caseId)))
            .thenReturn(List.of(states[0]),List.of(states[states.length-1]));
    }

    @Test void invalidPayloadDoesNotAccessDb(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.submitCount(tenant,observer,
                new LotReconciliationService.CountRequest(null,observation,"Checked source")))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.acknowledge(tenant,reviewer,transfer,
                new LotReconciliationService.AckRequest(key,-1,"RMA-123","Signed receipt")))
            .getStatusCode());
        verifyNoInteractions(jdbc,stock);
    }
    @Test void onlyAnObservationCanCreateCountEvidenceAndCannotModifyStock()
        throws Exception {
        active();observation(7);position();warehouse();stockBalance(20);
        countStored(count(7,"PENDING_REVIEW",null,null));
        var result=service.submitCount(tenant,observer,
            new LotReconciliationService.CountRequest(key,observation,
                "Physical cartons were recounted"));
        assertEquals("PENDING_REVIEW",result.status());
        verify(jdbc).update(contains("INSERT INTO erp_local_lot_count_review"),
            any(UUID.class),eq(tenant),eq(key),eq(observation),eq(position),
            eq(wh),eq(sku),eq(bin),eq(10),eq(7),eq(20L),
            eq("Physical cartons were recounted"),eq(observer));
        verifyNoInteractions(stock);
    }
    @Test void sourceObserverCannotApproveOwnStockAdjustment() throws Exception {
        active();observation(7);countStored(count(7,"PENDING_REVIEW",null,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.decideCount(tenant,observer,caseId,
                new LotReconciliationService.Decision("APPROVE",
                    "Independent recount completed"))).getStatusCode());
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void independentShortageApprovalPostsSingleV40CountAndPositionUpdate()
        throws Exception {
        active();warehouse();observation(7);position();stockBalance(20);
        countStored(count(7,"PENDING_REVIEW",null,null),
            count(7,"APPLIED",reviewer.toString(),operation.toString()));
        when(jdbc.update(contains("UPDATE erp_local_lot_position SET remaining_quantity=?"),
            eq(7),eq(tenant),eq(position),eq(10),eq(bin))).thenReturn(1);
        var stockOperation=new WarehouseStockOperationService.Operation(
            operation.toString(),"COUNT",wh.toString(),null,sku.toString(),
            17,20,-3,17,"V50 count adjustment",reviewer.toString(),
            "2026-11-01T00:00:00Z");
        when(stock.post(eq(tenant),eq(reviewer),
            any(WarehouseStockOperationService.Request.class))).thenReturn(stockOperation);
        var result=service.decideCount(tenant,reviewer,caseId,
            new LotReconciliationService.Decision("APPROVE",
                "Independent recount completed"));
        assertEquals("APPLIED",result.status());
        verify(stock).post(eq(tenant),eq(reviewer),
            argThat(req->req.type().equals("COUNT")&&req.quantity()==17
                &&req.expectedOnHand()==20&&req.warehouseId().equals(wh)
                &&req.productSkuId().equals(sku)));
        verify(jdbc).update(contains("UPDATE erp_local_lot_count_review"),
            eq("APPLIED"),eq(reviewer),eq("Independent recount completed"),
            eq(operation),eq(tenant),eq(caseId));
    }
    @Test void independentlyReviewedSurplusNeverFabricatesNewApprovedStock()
        throws Exception {
        active();warehouse();observation(13);position();stockBalance(20);
        countStored(count(13,"PENDING_REVIEW",null,null),
            count(13,"EVIDENCE_REQUIRED",reviewer.toString(),null));
        assertEquals("EVIDENCE_REQUIRED",service.decideCount(tenant,reviewer,caseId,
            new LotReconciliationService.Decision("APPROVE",
                "Independent recount completed")).status());
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(contains("erp_local_lot_position"),
            any(Object[].class));
        verify(jdbc).update(contains("UPDATE erp_local_lot_count_review"),
            eq("EVIDENCE_REQUIRED"),eq(reviewer),
            eq("Independent recount completed"),isNull(),eq(tenant),eq(caseId));
    }
    @Test void staleCountBalanceMustFailBeforeUpdatingPositionOrSignedStock()
        throws Exception {
        active();warehouse();observation(7);position();stockBalance(18);
        countStored(count(7,"PENDING_REVIEW",null,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.decideCount(tenant,reviewer,caseId,
                new LotReconciliationService.Decision("APPROVE",
                    "Independent recount completed"))).getStatusCode());
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void destinationOperatorCannotSelfAcknowledgeTransfer() throws Exception {
        active();
        var pending=new LotReconciliationService.Transfer(transfer.toString(),
            wh.toString(),position.toString(),position.toString(),8,0,
            "POSTED_AWAITING_DESTINATION",observer.toString(),null,null);
        when(jdbc.query(contains("FROM erp_local_lot_action a"),
            any(RowMapper.class),eq(tenant),eq(transfer))).thenReturn(List.of(pending));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.acknowledge(tenant,observer,transfer,
                new LotReconciliationService.AckRequest(key,8,"ASN-123",
                    "Warehouse receiving physically counted"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void destinationConfirmationIsEvidenceOnlyNotSecondTransfer() {
        active();
        var pending=new LotReconciliationService.Transfer(transfer.toString(),
            wh.toString(),position.toString(),position.toString(),8,0,
            "POSTED_AWAITING_DESTINATION",observer.toString(),null,null);
        var saved=new LotReconciliationService.Transfer(transfer.toString(),
            wh.toString(),position.toString(),position.toString(),8,6,
            "MISMATCH",observer.toString(),reviewer.toString(),"RCV-001");
        when(jdbc.query(contains("FROM erp_local_lot_action a"),
            any(RowMapper.class),eq(tenant),eq(transfer)))
            .thenReturn(List.of(pending),List.of(saved));
        assertEquals("MISMATCH",service.acknowledge(tenant,reviewer,transfer,
            new LotReconciliationService.AckRequest(key,6,"RCV-001",
                "Six physical cartons received")).status());
        verify(jdbc).update(contains("INSERT INTO erp_local_lot_transfer_ack"),
            any(UUID.class),eq(tenant),eq(key),eq(transfer),eq(wh),
            eq(8),eq(6),eq("MISMATCH"),eq("RCV-001"),
            eq("Six physical cartons received"),eq(reviewer));
        verifyNoInteractions(stock);
    }
    @Test void dispositionMustProveTheOriginalQuarantineAlreadyDebitedStock()
        throws Exception {
        active();
        when(jdbc.query(contains("FROM erp_local_lot_action"),
            any(RowMapper.class),eq(tenant),eq(quarantine))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("id",UUID.class)).thenReturn(quarantine);
                when(rs.getObject("source_warehouse_id",UUID.class)).thenReturn(wh);
                when(rs.getInt("quantity")).thenReturn(3);
                when(rs.getObject("actor_user_id",UUID.class)).thenReturn(observer);
                return List.of(mapper.mapRow(rs,0));
            });
        when(jdbc.queryForObject(contains("SELECT COUNT(*)::bigint FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(quarantine),eq(-3))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.proposeDisposition(tenant,observer,
                new LotReconciliationService.DispositionRequest(key,quarantine,
                    "DISPOSAL","Expired goods await documented disposal")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void approvedQuarantineDispositionReportsCompletionWithoutSecondDebit() {
        active();
        var pending=new LotReconciliationService.Disposition(
            dispositionId.toString(),quarantine.toString(),wh.toString(),3,
            "DISPOSAL","APPROVED","Expired cartons were quarantined",
            observer.toString(),reviewer.toString(),null,null);
        var complete=new LotReconciliationService.Disposition(
            dispositionId.toString(),quarantine.toString(),wh.toString(),3,
            "DISPOSAL","EXECUTED_REPORTED","Expired cartons were quarantined",
            observer.toString(),reviewer.toString(),observer.toString(),"DIS-1000");
        when(jdbc.query(contains("FROM erp_local_lot_disposition WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(dispositionId)))
            .thenReturn(List.of(pending),List.of(complete));
        assertEquals("EXECUTED_REPORTED",
            service.reportCompletion(tenant,observer,dispositionId,
                new LotReconciliationService.Completion(
                    "DIS-1000","Warehouse staff documented disposal")).status());
        verify(jdbc).update(contains("UPDATE erp_local_lot_disposition"),
            eq(observer),eq("DIS-1000"),eq("Warehouse staff documented disposal"),
            eq(tenant),eq(dispositionId));
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
}
