package org.merchivra.erp.purchasing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Small, private, immutable original-file attachments. The SHA-256 is calculated
 * by the server from actual uploaded bytes (never trusted from the browser).
 * Supplier/carrier authenticity is never asserted; production upload requires
 * a clean external ClamAV clamd verdict before storage (V137).
 */
@Service
public class LotProofBinaryService {
    public static final int MAX_SIZE=2*1024*1024;
    private final JdbcTemplate jdbc;
    private final LotProofAvScanner scanner;
    public LotProofBinaryService(JdbcTemplate jdbc,LotProofAvScanner scanner){
        this.jdbc=jdbc;this.scanner=scanner;
    }
    public record FileInfo(String id,String proofId,String mediaType,
        int sizeBytes,String sha256,String uploadedBy) {}
    public record Download(FileInfo info,byte[] bytes) {}
    private record Proof(String status,String assertedHash) {}
    private static ResponseStatusException bad(String message){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message){
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Private proof attachment unavailable");
    }
    static String mediaType(byte[] bytes){
        if(bytes==null || bytes.length<4 || bytes.length>MAX_SIZE)
            throw bad("Evidence file must be 1 byte to 2 MiB");
        if(bytes.length>=5 && bytes[0]=='%' && bytes[1]=='P' && bytes[2]=='D'
            && bytes[3]=='F' && bytes[4]=='-')return "application/pdf";
        if(bytes.length>=8 && (bytes[0]&255)==137 && bytes[1]==80
            && bytes[2]==78 && bytes[3]==71 && bytes[4]==13
            && bytes[5]==10 && bytes[6]==26 && bytes[7]==10)return "image/png";
        if((bytes[0]&255)==255 && (bytes[1]&255)==216
            && (bytes[2]&255)==255)return "image/jpeg";
        throw bad("Only PDF, PNG and JPEG evidence formats are accepted");
    }
    static String digest(byte[] bytes){
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void tenantLock(UUID tenant){
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private Proof proof(UUID tenant,UUID proofId){
        return jdbc.query("""
            SELECT status,document_sha256
            FROM erp_local_lot_disposition_proof
            WHERE tenant_id=? AND id=?
            """,(rs,i)->new Proof(rs.getString("status"),
                rs.getString("document_sha256")),tenant,proofId)
            .stream().findFirst().orElseThrow(LotProofBinaryService::missing);
    }
    private FileInfo info(UUID tenant,UUID proofId){
        return jdbc.query("""
            SELECT id,proof_id,media_type,size_bytes,sha256,uploaded_by
            FROM erp_local_lot_disposition_proof_file
            WHERE tenant_id=? AND proof_id=?
            """,(rs,i)->new FileInfo(rs.getObject("id",UUID.class).toString(),
                rs.getObject("proof_id",UUID.class).toString(),
                rs.getString("media_type"),rs.getInt("size_bytes"),
                rs.getString("sha256"),rs.getObject("uploaded_by",UUID.class).toString()),
            tenant,proofId).stream().findFirst().orElseThrow(LotProofBinaryService::missing);
    }
    @Transactional(readOnly=true)
    public FileInfo metadata(UUID tenant,UUID proofId){
        return info(tenant,proofId);
    }
    // V129: the file must NOT leave this transaction without a durable
    // tenant/actor/file/sha access record. Audit failure rolls the request back.
    // Never record the binary payload, bearer/session tokens, or filenames.
    @Transactional
    public Download download(UUID tenant,UUID actor,UUID proofId){
        if(actor==null || proofId==null)throw bad("Owner and proof required");
        FileInfo meta=info(tenant,proofId);
        byte[] bytes=jdbc.query("""
            SELECT bytes FROM erp_local_lot_disposition_proof_file
            WHERE tenant_id=? AND proof_id=?
            """,(rs,i)->rs.getBytes(1),tenant,proofId)
            .stream().findFirst().orElseThrow(LotProofBinaryService::missing);
        if(bytes.length!=meta.sizeBytes() || !digest(bytes).equals(meta.sha256()))
            throw conflict("Private original-file digest/length check failed");
        // V137: legacy V52 originals predate malware screening, so production
        // download MUST also require a fresh clean verdict, then durable V129
        // access audit. An unscanned or infected original never leaves the API.
        scanner.scan(bytes);
        int recorded=jdbc.update("""
            INSERT INTO erp_local_lot_proof_file_download_audit
              (id,tenant_id,proof_id,file_id,downloaded_by,sha256)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,proofId,UUID.fromString(meta.id()),actor,meta.sha256());
        if(recorded!=1)throw conflict("Private proof download audit unavailable");
        return new Download(meta,bytes);
    }
    @Transactional
    public FileInfo upload(UUID tenant,UUID actor,UUID proofId,UUID requestKey,byte[] data){
        if(proofId==null || requestKey==null)throw bad("Proof and request key required");
        String type=mediaType(data),sha=digest(data);
        // V137: no original bytes may enter PostgreSQL unless clamd replied
        // with exactly a clean verdict, except in an explicitly synthetic Demo.
        // Scan BEFORE acquiring tenant/warehouse locks; any error fails closed.
        scanner.scan(data);
        tenantLock(tenant);
        Proof p=proof(tenant,proofId);
        List<FileInfo> prior=jdbc.query("""
            SELECT id,proof_id,media_type,size_bytes,sha256,uploaded_by
            FROM erp_local_lot_disposition_proof_file
            WHERE tenant_id=? AND (request_key=? OR proof_id=?)
            """,(rs,i)->new FileInfo(rs.getObject("id",UUID.class).toString(),
                rs.getObject("proof_id",UUID.class).toString(),
                rs.getString("media_type"),rs.getInt("size_bytes"),
                rs.getString("sha256"),rs.getObject("uploaded_by",UUID.class).toString()),
            tenant,requestKey,proofId);
        if(!prior.isEmpty()){
            FileInfo old=prior.getFirst();
            List<UUID> keys=jdbc.queryForList("""
                SELECT request_key FROM erp_local_lot_disposition_proof_file
                WHERE tenant_id=? AND id=?
                """,UUID.class,tenant,UUID.fromString(old.id()));
            if(!old.proofId().equals(proofId.toString())
                || !old.uploadedBy().equals(actor.toString())
                || !old.mediaType().equals(type)
                || old.sizeBytes()!=data.length || !old.sha256().equals(sha)
                || keys.size()!=1 || !keys.getFirst().equals(requestKey))
                throw conflict("Proof already contains different immutable evidence");
            return old;
        }
        if(!"PENDING_REVIEW".equals(p.status()))
            throw conflict("Original files must be attached before proof review");
        if(p.assertedHash()!=null && !p.assertedHash().equals(sha))
            throw conflict("Original evidence SHA-256 does not match submitted hash");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_disposition_proof_file
              (id,tenant_id,proof_id,request_key,media_type,bytes,
               size_bytes,sha256,uploaded_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,id,tenant,proofId,requestKey,type,data,data.length,sha,actor);
        return info(tenant,proofId);
    }
}
