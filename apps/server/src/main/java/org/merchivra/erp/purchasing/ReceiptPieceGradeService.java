package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Immutable QC grades per receiving-stage piece, followed by a full V44 QC event.
 * Grade records neither post stock nor authorize release.
 */
@Service
public class ReceiptPieceGradeService {
    private final JdbcTemplate jdbc;
    private final PurchaseQualityService quality;
    public ReceiptPieceGradeService(JdbcTemplate jdbc,PurchaseQualityService quality) {
        this.jdbc=jdbc;this.quality=quality;
    }
    public record GradeRequest(UUID requestKey,UUID pieceId,int acceptedQuantity,
                               int rejectedQuantity,String reason) {}
    public record Grade(String id,String receiptId,String pieceId,int acceptedQuantity,
                        int rejectedQuantity,String reason,String inspectedBy,String createdAt) {}
    public record FinalizeRequest(UUID requestKey,String reason) {}
    private record Piece(int quantity,String receiptId) {}
    private record Totals(long graded,long pieces,long accepted,long rejected) {}
    private static ResponseStatusException bad(String m){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);
    }
    private static ResponseStatusException conflict(String m){
        return new ResponseStatusException(HttpStatus.CONFLICT,m);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Receiving QC piece unavailable");
    }
    private static String reason(String value){
        if(value==null || value.strip().length()<5 || value.strip().length()>300
           || value.chars().anyMatch(Character::isISOControl))
            throw bad("Grade reason must contain 5..300 printable characters");
        return value.strip();
    }
    private static Grade map(ResultSet rs,int i) throws SQLException {
        return new Grade(rs.getObject("id",UUID.class).toString(),
            rs.getObject("receipt_id",UUID.class).toString(),
            rs.getObject("piece_id",UUID.class).toString(),
            rs.getInt("accepted_quantity"),rs.getInt("rejected_quantity"),
            rs.getString("reason"),rs.getObject("inspected_by",UUID.class).toString(),
            rs.getObject("created_at",OffsetDateTime.class).toInstant().toString());
    }
    private static final String GRADE_SQL = """
        SELECT id,receipt_id,piece_id,accepted_quantity,rejected_quantity,
          reason,inspected_by,created_at
        FROM erp_receipt_piece_grade WHERE tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Grade> grades(UUID tenant,UUID receipt){
        return jdbc.query(GRADE_SQL+"""
            AND receipt_id=? ORDER BY created_at DESC,id DESC LIMIT 300
            """,ReceiptPieceGradeService::map,tenant,receipt);
    }
    private Grade get(UUID tenant,UUID id) {
        return jdbc.query(GRADE_SQL+" AND id=?",
            ReceiptPieceGradeService::map,tenant,id).stream().findFirst()
            .orElseThrow(ReceiptPieceGradeService::missing);
    }
    private void lockTenant(UUID tenant) {
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private int pendingReceipt(UUID tenant,UUID receipt){
        return jdbc.query("""
            SELECT received_quantity FROM erp_receipt_quality_batch
            WHERE tenant_id=? AND receipt_id=? AND status='PENDING_QC' FOR UPDATE
            """,(rs,i)->rs.getInt(1),tenant,receipt)
            .stream().findFirst().orElseThrow(
                ()->conflict("Only a still-uninspected receipt may be piece-graded"));
    }
    private Totals totals(UUID tenant,UUID receipt) {
        return jdbc.query("""
            SELECT (SELECT COUNT(*) FROM erp_receipt_piece_grade g
                     WHERE g.tenant_id=? AND g.receipt_id=?)::bigint AS graded,
                   (SELECT COUNT(*) FROM erp_receipt_lot_piece p
                     WHERE p.tenant_id=? AND p.receipt_id=?)::bigint AS pieces,
                   COALESCE((SELECT SUM(g.accepted_quantity) FROM erp_receipt_piece_grade g
                     WHERE g.tenant_id=? AND g.receipt_id=?),0)::bigint AS accepted,
                   COALESCE((SELECT SUM(g.rejected_quantity) FROM erp_receipt_piece_grade g
                     WHERE g.tenant_id=? AND g.receipt_id=?),0)::bigint AS rejected
            """,(rs,i)->new Totals(rs.getLong("graded"),rs.getLong("pieces"),
                rs.getLong("accepted"),rs.getLong("rejected")),
                tenant,receipt,tenant,receipt,tenant,receipt,tenant,receipt)
            .getFirst();
    }
    @Transactional
    public Grade grade(UUID tenant,UUID actor,UUID receipt,GradeRequest body) {
        if(receipt==null || body==null || body.requestKey()==null
           || body.pieceId()==null || body.acceptedQuantity()<0
           || body.rejectedQuantity()<0)
            throw bad("Missing grade source or invalid quantities");
        String detail=reason(body.reason());
        lockTenant(tenant);
        List<UUID> previous=jdbc.queryForList("""
            SELECT id FROM erp_receipt_piece_grade WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,body.requestKey());
        if(!previous.isEmpty()){
            Grade saved=get(tenant,previous.getFirst());
            if(!saved.receiptId().equals(receipt.toString())
               || !saved.pieceId().equals(body.pieceId().toString())
               || saved.acceptedQuantity()!=body.acceptedQuantity()
               || saved.rejectedQuantity()!=body.rejectedQuantity()
               || !saved.reason().equals(detail)
               || !saved.inspectedBy().equals(actor.toString()))
                throw conflict("Grade request key already used with different evidence");
            return saved;
        }
        pendingReceipt(tenant,receipt);
        Piece piece=jdbc.query("""
            SELECT receipt_id,quantity FROM erp_receipt_lot_piece
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new Piece(rs.getInt("quantity"),
                rs.getObject("receipt_id",UUID.class).toString()),
                tenant,body.pieceId()).stream().findFirst().orElseThrow(
                    ReceiptPieceGradeService::missing);
        if(!piece.receiptId().equals(receipt.toString()))
            throw missing();
        PurchaseQualityService.checkParts(piece.quantity(),body.acceptedQuantity(),
            body.rejectedQuantity());
        List<UUID> existing=jdbc.queryForList("""
            SELECT id FROM erp_receipt_piece_grade
            WHERE tenant_id=? AND piece_id=?
            """,UUID.class,tenant,body.pieceId());
        if(!existing.isEmpty())throw conflict("Receiving piece was already graded");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_receipt_piece_grade
              (id,tenant_id,receipt_id,piece_id,request_key,
               accepted_quantity,rejected_quantity,reason,inspected_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,id,tenant,receipt,body.pieceId(),body.requestKey(),
            body.acceptedQuantity(),body.rejectedQuantity(),detail,actor);
        return get(tenant,id);
    }
    @Transactional
    public PurchaseQualityService.Inspection finalizeReceipt(UUID tenant,UUID actor,
        UUID receipt,FinalizeRequest body) {
        if(receipt==null || body==null || body.requestKey()==null)
            throw bad("Missing inspection request key");
        String note=reason(body.reason());
        lockTenant(tenant);
        int physical=pendingReceipt(tenant,receipt);
        Totals total=totals(tenant,receipt);
        if(total.pieces()<1 || total.graded()!=total.pieces()
           || total.accepted()+total.rejected()!=physical)
            throw conflict("All receiving pieces must be graded with conserved quantities");
        return quality.inspect(tenant,actor,receipt,
            new PurchaseQualityService.InspectRequest(body.requestKey(),
                Math.toIntExact(total.accepted()),Math.toIntExact(total.rejected()),note));
    }
}
