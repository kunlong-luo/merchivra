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

class ReceiptPieceGradeServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseQualityService quality=mock(PurchaseQualityService.class);
    private final ReceiptPieceGradeService service=new ReceiptPieceGradeService(jdbc,quality);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID receipt=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID piece=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID key=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID qc=UUID.fromString("66666666-6666-4666-8666-666666666666");

    private void tenantAndPending() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.query(contains("SELECT received_quantity FROM erp_receipt_quality_batch"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenReturn(List.of(10));
    }
    @Test void invalidGradeCannotWriteOrReadDb() {
        var req=new ReceiptPieceGradeService.GradeRequest(key,piece,-1,11,"Bad packaging");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.grade(tenant,actor,receipt,req)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void pendingLotGradeMustMatchActualPieceQuantity() throws Exception {
        tenantAndPending();
        when(jdbc.query(contains("SELECT receipt_id,quantity FROM erp_receipt_lot_piece"),
            any(RowMapper.class),eq(tenant),eq(piece))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getInt("quantity")).thenReturn(10);
                when(rs.getObject("receipt_id",UUID.class)).thenReturn(receipt);
                return List.of(mapper.mapRow(rs,0));
            });
        var req=new ReceiptPieceGradeService.GradeRequest(key,piece,6,3,"Bad packaging");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.grade(tenant,actor,receipt,req)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void anIdenticalGradeKeyReplaysWithoutAnotherStockOrGradeWrite() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.queryForList(contains("FROM erp_receipt_piece_grade"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of(qc));
        var saved=new ReceiptPieceGradeService.Grade(qc.toString(),receipt.toString(),
            piece.toString(),7,3,"Bad packaging",actor.toString(),
            "2026-11-01T00:00:00Z");
        when(jdbc.query(contains("FROM erp_receipt_piece_grade WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(qc))).thenReturn(List.of(saved));
        assertEquals(qc.toString(),service.grade(tenant,actor,receipt,
            new ReceiptPieceGradeService.GradeRequest(key,piece,7,3,
                "Bad packaging")).id());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cannotFinalizeReceiptWithoutCompletePieceEvidence() throws Exception {
        tenantAndPending();
        when(jdbc.query(contains("SELECT (SELECT COUNT(*) FROM erp_receipt_piece_grade"),
            any(RowMapper.class),eq(tenant),eq(receipt),eq(tenant),eq(receipt),
            eq(tenant),eq(receipt),eq(tenant),eq(receipt))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getLong("graded")).thenReturn(1L);
                when(rs.getLong("pieces")).thenReturn(2L);
                when(rs.getLong("accepted")).thenReturn(4L);
                when(rs.getLong("rejected")).thenReturn(1L);
                return List.of(mapper.mapRow(rs,0));
            });
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeReceipt(tenant,actor,receipt,
                new ReceiptPieceGradeService.FinalizeRequest(key,
                    "Completed all piece inspections"))).getStatusCode());
        verifyNoInteractions(quality);
    }
    @Test void completePieceTotalsUseTheExistingSingleReceiptQcAndDoNotPostStock()
        throws Exception {
        tenantAndPending();
        when(jdbc.query(contains("SELECT (SELECT COUNT(*) FROM erp_receipt_piece_grade"),
            any(RowMapper.class),eq(tenant),eq(receipt),eq(tenant),eq(receipt),
            eq(tenant),eq(receipt),eq(tenant),eq(receipt))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getLong("graded")).thenReturn(2L);
                when(rs.getLong("pieces")).thenReturn(2L);
                when(rs.getLong("accepted")).thenReturn(7L);
                when(rs.getLong("rejected")).thenReturn(3L);
                return List.of(mapper.mapRow(rs,0));
            });
        var inspected=new PurchaseQualityService.Inspection(qc.toString(),
            receipt.toString(),piece.toString(),piece.toString(),10,7,3,
            "Completed all piece inspections",actor.toString(),"2026-11-01T00:00:00Z");
        when(quality.inspect(eq(tenant),eq(actor),eq(receipt),
            any(PurchaseQualityService.InspectRequest.class))).thenReturn(inspected);
        assertEquals(7,service.finalizeReceipt(tenant,actor,receipt,
            new ReceiptPieceGradeService.FinalizeRequest(key,
                "Completed all piece inspections")).acceptedQuantity());
        verify(quality).inspect(eq(tenant),eq(actor),eq(receipt),
            argThat(x->x.requestKey().equals(key)&&x.acceptedQuantity()==7
                &&x.rejectedQuantity()==3));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
}
