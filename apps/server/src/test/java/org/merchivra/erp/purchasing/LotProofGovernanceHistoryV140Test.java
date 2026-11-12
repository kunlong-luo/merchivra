package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

class LotProofGovernanceHistoryV140Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotProofGovernanceService service=new LotProofGovernanceService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID proof=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID file=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID actor=UUID.fromString("44444444-4444-4444-8444-444444444444");

    private void allowExistingProof()throws Exception {
        when(jdbc.query(contains("FROM erp_local_lot_disposition_proof_file"),
            any(RowMapper.class),eq(tenant),eq(proof))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("id",UUID.class)).thenReturn(file);
            when(rs.getObject("uploaded_by",UUID.class)).thenReturn(actor);
            return List.of(mapper.mapRow(rs,0));
        });
    }

    @Test void incorrectPageLimitOrCursorScopeOrDatesRejectBeforeSql(){
        var invalid=List.of(
            LocalKeysetPage.encode("WRONG",tenant.toString(),proof.toString(),
                "2026-11-12T00:00:00Z",UUID.randomUUID().toString()),
            LocalKeysetPage.encode("V140_PROOF_GOV_AUDIT",UUID.randomUUID().toString(),
                proof.toString(),"2026-11-12T00:00:00Z",UUID.randomUUID().toString()),
            LocalKeysetPage.encode("V140_PROOF_GOV_AUDIT",tenant.toString(),
                UUID.randomUUID().toString(),"2026-11-12T00:00:00Z",UUID.randomUUID().toString()),
            LocalKeysetPage.encode("V140_PROOF_GOV_AUDIT",tenant.toString(),
                proof.toString(),"garbage",UUID.randomUUID().toString()),
            LocalKeysetPage.encode("V140_PROOF_GOV_AUDIT",tenant.toString(),
                proof.toString(),"2026-11-12T00:00:00Z","not-a-uuid"),
            "not-valid!base64");
        for(String c:invalid)assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.historyPage(tenant,proof,50,c)).getStatusCode());
        for(int size:new int[]{0,-5,101})assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.historyPage(tenant,proof,size,null)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void indexedReadLimit51StableCursorAndNoByteQueries()throws Exception{
        allowExistingProof();
        when(jdbc.query(contains("FROM erp_lot_proof_file_governance_audit"),
            any(RowMapper.class),any(Object[].class))).thenAnswer(inv->{
            String sql=inv.getArgument(0);
            assertTrue(sql.contains("WHERE tenant_id=? AND proof_id=?"));
            assertTrue(sql.contains("ORDER BY recorded_at DESC,id DESC LIMIT ?"));
            assertFalse(sql.toLowerCase().contains("delete "));
            Object[] params=inv.getArgument(2);
            assertEquals(3,params.length);
            assertEquals(tenant,params[0]);
            assertEquals(proof,params[1]);
            assertEquals(51,params[2]);
            RowMapper<?> mapper=inv.getArgument(1);
            var result=new ArrayList<>();
            for(int i=0;i<51;i++){
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("id",UUID.class)).thenReturn(
                    new UUID(0,i+1L));
                when(rs.getObject("actor_user_id",UUID.class)).thenReturn(actor);
                when(rs.getObject("recorded_at",OffsetDateTime.class)).thenReturn(
                    OffsetDateTime.parse("2026-11-12T00:00:00Z").minusSeconds(i));
                when(rs.getString("action")).thenReturn("PLACE_HOLD");
                when(rs.getString("reason")).thenReturn("Synthetic governance event");
                result.add(mapper.mapRow(rs,i));
            }
            return result;
        });
        var page=service.historyPage(tenant,proof,50,null);
        assertEquals(50,page.items().size());
        assertTrue(page.hasMore());
        String[] values=LocalKeysetPage.decode(page.nextCursor(),5);
        assertArrayEquals(new String[]{"V140_PROOF_GOV_AUDIT",
            tenant.toString(),proof.toString(),"2026-11-11T23:59:11Z",
            new UUID(0,50).toString()},values);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void deepCursorUsesTuplePredicateAndSameTenantProof()throws Exception{
        allowExistingProof();
        when(jdbc.query(contains("FROM erp_lot_proof_file_governance_audit"),
            any(RowMapper.class),any(Object[].class))).thenAnswer(inv->{
            String sql=inv.getArgument(0);
            assertTrue(sql.contains("(recorded_at,id)<(?,?)"));
            Object[] args=inv.getArgument(2);
            assertEquals(5,args.length);
            assertEquals(OffsetDateTime.parse("2026-11-12T01:02:03Z"),args[2]);
            assertEquals(file,args[3]);
            assertEquals(51,args[4]);
            return List.of();
        });
        String cursor=LocalKeysetPage.encode("V140_PROOF_GOV_AUDIT",
            tenant.toString(),proof.toString(),"2026-11-12T01:02:03Z",file.toString());
        var result=service.historyPage(tenant,proof,50,cursor);
        assertFalse(result.hasMore());
        assertNull(result.nextCursor());
    }
}
