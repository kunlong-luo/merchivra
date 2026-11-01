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

class LotDispositionProofServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotDispositionProofService service=new LotDispositionProofService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID other=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID disposition=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID key=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID proof=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String note="Manual evidence reference recorded for internal review";

    private LotDispositionProofService.Submit submission(String kind,String hash){
        return new LotDispositionProofService.Submit(key,disposition,kind,
            "PROOF-001",hash,note);
    }
    private void active(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void completed(String method,UUID completedBy) throws Exception {
        when(jdbc.query(contains("FROM erp_local_lot_disposition"),
            any(RowMapper.class),eq(tenant),eq(disposition))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getString("method")).thenReturn(method);
            when(rs.getString("status")).thenReturn("EXECUTED_REPORTED");
            when(rs.getObject("completed_by",UUID.class)).thenReturn(completedBy);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    private void pending(){
        var record=new LotDispositionProofService.Proof(proof.toString(),
            disposition.toString(),"DISPOSAL_CERTIFICATE","PROOF-001",
            null,note,actor.toString(),"PENDING_REVIEW",null,null);
        when(jdbc.query(contains("FROM erp_local_lot_disposition_proof WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(proof))).thenReturn(List.of(record));
    }
    @Test void malformedHashOrWrongProofKindFailsBeforeDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.submit(tenant,actor,submission("DISPOSAL_CERTIFICATE","123")))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.submit(tenant,actor,submission("FILE_UPLOAD",null)))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void supplierReturnCannotUseDisposalCertificate() throws Exception {
        active();completed("SUPPLIER_RETURN",actor);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.submit(tenant,actor,
                submission("DISPOSAL_CERTIFICATE",null))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void submittingExecutorCannotReviewTheirOwnMetadata() throws Exception{
        active();completed("DISPOSAL",actor);pending();
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.decide(tenant,actor,proof,
                new LotDispositionProofService.Decision("REVIEW",
                    "Internal metadata fields inspected"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void independentlyReviewedMetadataNeverMovesSignedStock() throws Exception{
        active();completed("DISPOSAL",actor);pending();
        var completed=new LotDispositionProofService.Proof(proof.toString(),
            disposition.toString(),"DISPOSAL_CERTIFICATE","PROOF-001",
            null,note,actor.toString(),"METADATA_REVIEWED",
            other.toString(),"Internal metadata fields inspected");
        when(jdbc.query(contains("FROM erp_local_lot_disposition_proof WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(proof)))
            .thenReturn(List.of(new LotDispositionProofService.Proof(proof.toString(),
                disposition.toString(),"DISPOSAL_CERTIFICATE","PROOF-001",
                null,note,actor.toString(),"PENDING_REVIEW",null,null)),
                List.of(completed));
        assertEquals("METADATA_REVIEWED",service.decide(tenant,other,proof,
            new LotDispositionProofService.Decision("REVIEW",
                "Internal metadata fields inspected")).status());
        verify(jdbc).update(contains("UPDATE erp_local_lot_disposition_proof"),
            eq("METADATA_REVIEWED"),eq(other),
            eq("Internal metadata fields inspected"),eq(tenant),eq(proof));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
}
