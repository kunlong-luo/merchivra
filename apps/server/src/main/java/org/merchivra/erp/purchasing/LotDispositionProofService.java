package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** V51 internal review of manually supplied proof metadata.
 * This never uploads files, verifies external receipts or touches stock.
 */
@Service
public class LotDispositionProofService {
    private final JdbcTemplate jdbc;
    public LotDispositionProofService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Submit(UUID requestKey,UUID dispositionId,String proofKind,
                         String evidenceReference,String documentSha256,String explanation) {}
    public record Decision(String decision,String reason) {}
    public record Proof(String id,String dispositionId,String proofKind,
                        String evidenceReference,String documentSha256,
                        String explanation,String submittedBy,String status,
                        String reviewedBy,String reviewReason) {}
    private record Disposition(String method,String status,UUID completedBy) {}
    private static ResponseStatusException bad(String msg){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);
    }
    private static ResponseStatusException conflict(String msg){
        return new ResponseStatusException(HttpStatus.CONFLICT,msg);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Disposition proof unavailable");
    }
    private static String reason(String s){
        if(s==null||s.strip().length()<5||s.strip().length()>300
            ||s.chars().anyMatch(Character::isISOControl))
            throw bad("Proof explanation must be 5..300 printable characters");
        return s.strip();
    }
    private static String reference(String s){
        if(s==null||s.strip().length()<4||s.strip().length()>120
           ||s.chars().anyMatch(Character::isISOControl))
            throw bad("Evidence reference must be 4..120 printable characters");
        return s.strip();
    }
    private static String checksum(String s){
        if(s==null || s.isBlank())return null;
        if(!s.matches("[a-f0-9]{64}"))
            throw bad("Optional document SHA-256 must be 64 lowercase hex digits");
        return s;
    }
    private static Proof map(ResultSet rs,int index) throws SQLException{
        UUID reviewer=rs.getObject("reviewed_by",UUID.class);
        return new Proof(rs.getObject("id",UUID.class).toString(),
            rs.getObject("disposition_id",UUID.class).toString(),
            rs.getString("proof_kind"),rs.getString("evidence_reference"),
            rs.getString("document_sha256"),rs.getString("explanation"),
            rs.getObject("submitted_by",UUID.class).toString(),
            rs.getString("status"),reviewer==null?null:reviewer.toString(),
            rs.getString("review_reason"));
    }
    private static final String SQL = """
        SELECT id,disposition_id,proof_kind,evidence_reference,
          document_sha256,explanation,submitted_by,status,reviewed_by,review_reason
        FROM erp_local_lot_disposition_proof WHERE tenant_id=?
        """;
    private Proof one(UUID tenant,UUID id){
        return jdbc.query(SQL+" AND id=?",LotDispositionProofService::map,tenant,id)
            .stream().findFirst().orElseThrow(LotDispositionProofService::missing);
    }
    @Transactional(readOnly=true)
    public List<Proof> list(UUID tenant,UUID warehouse){
        return jdbc.query(SQL+"""
            AND disposition_id IN (
              SELECT id FROM erp_local_lot_disposition
              WHERE tenant_id=? AND warehouse_id=?
            )
            ORDER BY created_at DESC,id DESC LIMIT 200
            """,LotDispositionProofService::map,tenant,tenant,warehouse);
    }
    private void tenantLock(UUID tenant){
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private Disposition source(UUID tenant,UUID id){
        return jdbc.query("""
            SELECT method,status,completed_by FROM erp_local_lot_disposition
            WHERE tenant_id=? AND id=?
            """,(rs,i)->new Disposition(rs.getString("method"),
                rs.getString("status"),rs.getObject("completed_by",UUID.class)),
            tenant,id).stream().findFirst().orElseThrow(LotDispositionProofService::missing);
    }
    @Transactional
    public Proof submit(UUID tenant,UUID actor,Submit req){
        if(req==null || req.requestKey()==null || req.dispositionId()==null
            || !List.of("DISPOSAL_CERTIFICATE","SUPPLIER_RETURN_RECEIPT")
                .contains(req.proofKind()))
            throw bad("Invalid proof kind or source");
        String ref=reference(req.evidenceReference()),
            note=reason(req.explanation()),sha=checksum(req.documentSha256());
        tenantLock(tenant);
        List<UUID> keys=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_disposition_proof
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!keys.isEmpty()){
            Proof old=one(tenant,keys.getFirst());
            if(!old.dispositionId().equals(req.dispositionId().toString())
                || !old.submittedBy().equals(actor.toString())
                || !old.proofKind().equals(req.proofKind())
                || !old.evidenceReference().equals(ref)
                || !Objects.equals(old.documentSha256(),sha)
                || !old.explanation().equals(note))
                throw conflict("Proof key used with another submission");
            return old;
        }
        Disposition source=source(tenant,req.dispositionId());
        if(!"EXECUTED_REPORTED".equals(source.status()))
            throw conflict("Physical disposition must be reported before proof review");
        if(("DISPOSAL".equals(source.method()) &&
            !"DISPOSAL_CERTIFICATE".equals(req.proofKind()))
          || ("SUPPLIER_RETURN".equals(source.method()) &&
            !"SUPPLIER_RETURN_RECEIPT".equals(req.proofKind())))
            throw conflict("Evidence type does not match approved disposition method");
        List<UUID> claimed=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_disposition_proof
            WHERE tenant_id=? AND disposition_id=?
            """,UUID.class,tenant,req.dispositionId());
        if(!claimed.isEmpty())throw conflict("Disposition already has proof metadata");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_disposition_proof
              (id,tenant_id,request_key,disposition_id,proof_kind,
               evidence_reference,document_sha256,explanation,submitted_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),req.dispositionId(),
            req.proofKind(),ref,sha,note,actor);
        return one(tenant,id);
    }
    @Transactional
    public Proof decide(UUID tenant,UUID actor,UUID id,Decision req){
        if(id==null || req==null || !List.of("REVIEW","REJECT").contains(req.decision()))
            throw bad("Only REVIEW or REJECT is supported");
        String note=reason(req.reason());
        tenantLock(tenant);
        Proof prior=one(tenant,id);
        if(!"PENDING_REVIEW".equals(prior.status())){
            String intended="REVIEW".equals(req.decision())?"METADATA_REVIEWED":"REJECTED";
            if(intended.equals(prior.status())&&actor.toString().equals(prior.reviewedBy())
               &&note.equals(prior.reviewReason()))return prior;
            throw conflict("Proof already reviewed");
        }
        Disposition src=source(tenant,UUID.fromString(prior.dispositionId()));
        if(!"EXECUTED_REPORTED".equals(src.status()))
            throw conflict("Proof source changed");
        if(actor.toString().equals(prior.submittedBy())
           || actor.equals(src.completedBy()))
            throw conflict("Proof submitter/executor cannot review own evidence");
        jdbc.update("""
            UPDATE erp_local_lot_disposition_proof
            SET status=?,reviewed_by=?,review_reason=?,reviewed_at=now()
            WHERE tenant_id=? AND id=? AND status='PENDING_REVIEW'
            ""","REVIEW".equals(req.decision())?"METADATA_REVIEWED":"REJECTED",
            actor,note,tenant,id);
        // Internal metadata review only. No external verification or stock mutation.
        return one(tenant,id);
    }
}
