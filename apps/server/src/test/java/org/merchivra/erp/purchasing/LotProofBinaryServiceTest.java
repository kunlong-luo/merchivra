package org.merchivra.erp.purchasing;

import java.util.HexFormat;
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

class LotProofBinaryServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotProofBinaryService service=new LotProofBinaryService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID proof=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID key=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID fileId=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final byte[] PDF="%PDF-1.7\nreal original bytes\n%%EOF".getBytes(
        java.nio.charset.StandardCharsets.UTF_8);
    private void tenantActive(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void source(String status,String hash) throws Exception{
        when(jdbc.query(contains("FROM erp_local_lot_disposition_proof"),
            any(RowMapper.class),eq(tenant),eq(proof))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            var rs=mock(java.sql.ResultSet.class);
            when(rs.getString("status")).thenReturn(status);
            when(rs.getString("document_sha256")).thenReturn(hash);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    @Test void checksActualByteMagicNotFilenameOrMimeHeader(){
        assertEquals("application/pdf",LotProofBinaryService.mediaType(PDF));
        assertEquals("image/png",LotProofBinaryService.mediaType(new byte[]{
            (byte)137,80,78,71,13,10,26,10,0}));
        assertEquals("image/jpeg",LotProofBinaryService.mediaType(
            new byte[]{(byte)255,(byte)216,(byte)255,0}));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->LotProofBinaryService.mediaType("<html>fake.pdf".getBytes()))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->LotProofBinaryService.mediaType(new byte[LotProofBinaryService.MAX_SIZE+1]))
            .getStatusCode());
    }
    @Test void computesServerSha256OfActualFileBytes(){
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            LotProofBinaryService.digest("hello".getBytes()));
    }
    @Test void rejectsForgedClientHashBeforePersistingBinary() throws Exception {
        tenantActive();source("PENDING_REVIEW","a".repeat(64));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.upload(tenant,actor,proof,key,PDF)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cannotAttachNewFileToAlreadyReviewedProof() throws Exception {
        tenantActive();source("METADATA_REVIEWED",null);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.upload(tenant,actor,proof,key,PDF)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void uploadUsesExactStoredProofAndUniqueRequestKey() throws Exception {
        tenantActive();source("PENDING_REVIEW",LotProofBinaryService.digest(PDF));
        var info=new LotProofBinaryService.FileInfo(fileId.toString(),proof.toString(),
            "application/pdf",PDF.length,LotProofBinaryService.digest(PDF),
            actor.toString());
        when(jdbc.query(contains("FROM erp_local_lot_disposition_proof_file"),
            any(RowMapper.class),eq(tenant),eq(proof))).thenReturn(List.of(info));
        assertEquals(info,service.upload(tenant,actor,proof,key,PDF));
        verify(jdbc).update(contains("INSERT INTO erp_local_lot_disposition_proof_file"),
            any(UUID.class),eq(tenant),eq(proof),eq(key),eq("application/pdf"),
            aryEq(PDF),eq(PDF.length),eq(LotProofBinaryService.digest(PDF)),eq(actor));
    }
}
