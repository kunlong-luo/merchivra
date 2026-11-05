package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V47 receiving-stage receipt partitions, NOT saleable lot or serial-level stock.
 * Splitting or relocating gross held quantities never creates stock movements.
 */
@Service
public class ReceiptLotLocationService {
    private final JdbcTemplate jdbc;
    public ReceiptLotLocationService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Bin(String id,String warehouseId,String code,String purpose,String status) {}
    public record NewBin(String code,String purpose) {}
    public record PiecePage(List<Piece> items,String nextCursor,boolean hasMore,
                            int pageSize,long totalQuantity) {}
    public record Piece(String id,String receiptId,String warehouseId,String productSkuId,
                        String binId,String pieceCode,int quantity) {}
    public record Split(UUID requestKey,UUID pieceId,int quantity,String pieceCode,
                        UUID destinationBinId,String reason) {}
    public record Move(UUID requestKey,UUID pieceId,UUID expectedBinId,
                       UUID destinationBinId,String reason) {}
    public record Pressure(String warehouseId,String productSkuId,String internalSku,
                           long usableUnits,long pendingQcUnits,
                           long acceptedAwaitingReleaseUnits,long declaredAsnInTransitUnits) {}

    private record Action(String type,UUID receiptId,UUID pieceId,UUID relatedId,int quantity,
                          UUID oldBin,UUID newBin,String reason,UUID actor) {}
    private static ResponseStatusException bad(String message){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message){
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Local lot or warehouse unavailable");
    }
    private static String code(String value){
        if(value==null || !value.matches("[A-Z0-9][A-Z0-9_-]{1,63}"))
            throw bad("Location code must be 2..64 safe uppercase characters");
        return value;
    }
    private static String pieceCode(String value){
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{1,79}"))
            throw bad("Lot piece code must be 2..80 safe characters");
        return value;
    }
    private static String reason(String value){
        if(value==null || value.strip().length()<5 || value.strip().length()>300
           || value.chars().anyMatch(Character::isISOControl))
            throw bad("Reason must contain 5..300 printable characters");
        return value.strip();
    }
    private void tenantLock(UUID tenant){
        List<UUID> ids=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(ids.size()!=1)throw missing();
    }
    private void activeWarehouse(UUID tenant,UUID warehouse){
        List<UUID> ids=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR SHARE
            """,UUID.class,tenant,warehouse);
        if(ids.size()!=1)throw missing();
    }
    private static Bin mapBin(ResultSet rs,int i) throws SQLException {
        return new Bin(rs.getObject("id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getString("bin_code"),rs.getString("purpose"),rs.getString("status"));
    }
    @Transactional(readOnly=true)
    public List<Bin> bins(UUID tenant,UUID warehouse){
        return jdbc.query("""
            SELECT id,warehouse_id,bin_code,purpose,status
            FROM erp_local_stock_bin
            WHERE tenant_id=? AND warehouse_id=?
            ORDER BY bin_code LIMIT 200
            """,ReceiptLotLocationService::mapBin,tenant,warehouse);
    }
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Bin> binsPage(UUID tenant,UUID warehouse,
                                             int requested,String cursor) {
        if(warehouse==null)throw bad("Warehouse is required");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,2);
        if(after!=null && !warehouse.toString().equals(after[0]))
            throw bad("Bin cursor belongs to a different warehouse");
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(warehouse);
        String where="";
        if(after!=null) {
            where=" AND bin_code > ?";
            args.add(after[1]);
        }
        args.add(limit+1);
        List<Bin> rows=jdbc.query("""
            SELECT id,warehouse_id,bin_code,purpose,status
            FROM erp_local_stock_bin
            WHERE tenant_id=? AND warehouse_id=?
            """+where+" ORDER BY bin_code LIMIT ?",
            ReceiptLotLocationService::mapBin,args.toArray());
        return LocalKeysetPage.pack(rows,limit,
            b->LocalKeysetPage.encode(warehouse.toString(),b.code()));
    }
    @Transactional
    public Bin createBin(UUID tenant,UUID actor,UUID warehouse,NewBin body){
        if(warehouse==null || body==null
            || body.purpose()==null || !List.of("QC_HOLD","STORAGE").contains(body.purpose()))
            throw bad("Invalid location kind");
        String safe=code(body.code());
        if("QC-HOLD".equals(safe))throw bad("QC-HOLD is a reserved system location");
        tenantLock(tenant);
        activeWarehouse(tenant,warehouse);
        UUID id=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO erp_local_stock_bin
              (id,tenant_id,warehouse_id,bin_code,purpose,created_by)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (tenant_id,warehouse_id,bin_code) DO NOTHING
            """,id,tenant,warehouse,safe,body.purpose(),actor);
        if(inserted!=1)throw conflict("Warehouse location already exists");
        return new Bin(id.toString(),warehouse.toString(),safe,body.purpose(),"ACTIVE");
    }
    private static Piece mapPiece(ResultSet rs,int i) throws SQLException {
        return new Piece(rs.getObject("id",UUID.class).toString(),
            rs.getObject("receipt_id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getObject("bin_id",UUID.class).toString(),
            rs.getString("piece_code"),rs.getInt("quantity"));
    }
    private static final String PIECE_SQL = """
        SELECT id,receipt_id,warehouse_id,product_sku_id,bin_id,piece_code,quantity
        FROM erp_receipt_lot_piece
        WHERE tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Piece> pieces(UUID tenant,UUID receipt){
        return jdbc.query(PIECE_SQL+"""
            AND receipt_id=? ORDER BY piece_code,id LIMIT 200
            """,ReceiptLotLocationService::mapPiece,tenant,receipt);
    }
    /** The total is calculated from all tenant-owned pieces, never the current page. */
    @Transactional(readOnly=true)
    public PiecePage piecesPage(UUID tenant,UUID receipt,int requested,String cursor) {
        if(receipt==null)throw bad("Receipt is required");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,2);
        if(after!=null && !receipt.toString().equals(after[0]))
            throw bad("Piece cursor belongs to a different receipt");
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(receipt);
        String where="";
        if(after!=null) {
            where=" AND piece_code > ?";
            args.add(after[1]);
        }
        args.add(limit+1);
        List<Piece> rows=jdbc.query(PIECE_SQL+" AND receipt_id=?"+where+
            " ORDER BY piece_code LIMIT ?",
            ReceiptLotLocationService::mapPiece,args.toArray());
        var page=LocalKeysetPage.pack(rows,limit,
            p->LocalKeysetPage.encode(receipt.toString(),p.pieceCode()));
        Long total=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_receipt_lot_piece WHERE tenant_id=? AND receipt_id=?
            """,Long.class,tenant,receipt);
        return new PiecePage(page.items(),page.nextCursor(),page.hasMore(),
            page.pageSize(),total==null?0:total);
    }
    private Piece piece(UUID tenant,UUID id) {
        return jdbc.query(PIECE_SQL+" AND id=?",
            ReceiptLotLocationService::mapPiece,tenant,id)
            .stream().findFirst().orElseThrow(ReceiptLotLocationService::missing);
    }
    private void lockPendingBatch(UUID tenant,UUID receipt){
        List<UUID> ids=jdbc.queryForList("""
            SELECT receipt_id FROM erp_receipt_quality_batch
            WHERE tenant_id=? AND receipt_id=? AND status='PENDING_QC' FOR UPDATE
            """,UUID.class,tenant,receipt);
        if(ids.size()!=1)throw conflict("Only uninspected receiving lots may be partitioned or moved");
        Long graded=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_receipt_piece_grade
            WHERE tenant_id=? AND receipt_id=?
            """,Long.class,tenant,receipt);
        if(graded!=null && graded>0)
            throw conflict("Receiving lot pieces are frozen once quality grading starts");
    }
    private void requireQcBin(UUID tenant,UUID warehouse,UUID binId){
        if(binId==null)throw bad("QC-hold location required");
        List<UUID> ids=jdbc.queryForList("""
            SELECT id FROM erp_local_stock_bin
            WHERE tenant_id=? AND warehouse_id=? AND id=?
              AND purpose='QC_HOLD' AND status='ACTIVE'
            """,UUID.class,tenant,warehouse,binId);
        if(ids.size()!=1)throw missing();
    }
    private Action action(UUID tenant,UUID key) {
        return jdbc.query("""
            SELECT event_type,receipt_id,piece_id,related_piece_id,
              quantity,old_bin_id,new_bin_id,note,actor_user_id
            FROM erp_receipt_lot_piece_event
            WHERE tenant_id=? AND request_key=?
            """,(rs,i)->new Action(rs.getString("event_type"),
                rs.getObject("receipt_id",UUID.class),
                rs.getObject("piece_id",UUID.class),
                rs.getObject("related_piece_id",UUID.class),
                rs.getInt("quantity"),rs.getObject("old_bin_id",UUID.class),
                rs.getObject("new_bin_id",UUID.class),rs.getString("note"),
                rs.getObject("actor_user_id",UUID.class)),
            tenant,key).stream().findFirst().orElse(null);
    }
    private void event(UUID tenant,UUID actor,UUID receipt,UUID source,UUID destination,
                       UUID key,String kind,int quantity,UUID before,UUID after,String note){
        jdbc.update("""
            INSERT INTO erp_receipt_lot_piece_event
              (id,tenant_id,receipt_id,piece_id,related_piece_id,request_key,
               actor_user_id,event_type,quantity,old_bin_id,new_bin_id,note)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,receipt,source,destination,key,actor,
            kind,quantity,before,after,note);
    }
    @Transactional
    public List<Piece> split(UUID tenant,UUID actor,UUID receipt,Split body){
        if(receipt==null || body==null || body.requestKey()==null
            || body.pieceId()==null || body.destinationBinId()==null
            || body.quantity()<1 || body.quantity()>1_000_000)
            throw bad("Split requires explicit target QC-hold location and request key");
        String label=pieceCode(body.pieceCode());
        String explanation=reason(body.reason());
        tenantLock(tenant);
        Action old=action(tenant,body.requestKey());
        if(old!=null) {
            if(!"SPLIT".equals(old.type()) || !old.actor().equals(actor)
                || !old.receiptId().equals(receipt)
                || !old.pieceId().equals(body.pieceId())
                || old.quantity()!=body.quantity()
                || !Objects.equals(old.newBin(),body.destinationBinId())
                || !old.reason().equals(explanation)
                || !label.equals(piece(tenant,old.relatedId()).pieceCode()))
                throw conflict("Split request key used for another action");
            return pieces(tenant,receipt);
        }
        lockPendingBatch(tenant,receipt);
        Piece source=piece(tenant,body.pieceId());
        if(!receipt.toString().equals(source.receiptId()))throw missing();
        if(body.quantity()>=source.quantity())throw conflict("Split must leave positive source quantity");
        UUID sourceBin=UUID.fromString(source.binId());
        UUID newBin=body.destinationBinId()==null?sourceBin:body.destinationBinId();
        requireQcBin(tenant,UUID.fromString(source.warehouseId()),newBin);
        UUID child=UUID.randomUUID();
        int changed=jdbc.update("""
            UPDATE erp_receipt_lot_piece SET quantity=quantity-?,updated_at=now()
            WHERE tenant_id=? AND id=? AND quantity=?
            """,body.quantity(),tenant,body.pieceId(),source.quantity());
        if(changed!=1)throw conflict("Source lot piece changed");
        jdbc.update("""
            INSERT INTO erp_receipt_lot_piece
              (id,tenant_id,receipt_id,warehouse_id,product_sku_id,
               bin_id,piece_code,quantity,created_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,child,tenant,receipt,UUID.fromString(source.warehouseId()),
            UUID.fromString(source.productSkuId()),newBin,label,body.quantity(),actor);
        event(tenant,actor,receipt,body.pieceId(),child,body.requestKey(),
            "SPLIT",body.quantity(),sourceBin,newBin,explanation);
        return pieces(tenant,receipt);
    }
    @Transactional
    public List<Piece> move(UUID tenant,UUID actor,UUID receipt,Move body){
        if(receipt==null || body==null || body.requestKey()==null || body.pieceId()==null
            || body.expectedBinId()==null || body.destinationBinId()==null)
            throw bad("Invalid lot location movement");
        String explanation=reason(body.reason());
        if(body.expectedBinId().equals(body.destinationBinId()))
            throw bad("Destination must differ from existing location");
        tenantLock(tenant);
        Action old=action(tenant,body.requestKey());
        if(old!=null) {
            if(!"MOVED".equals(old.type()) || !old.actor().equals(actor)
                || !old.receiptId().equals(receipt)
                || !old.pieceId().equals(body.pieceId())
                || !Objects.equals(old.oldBin(),body.expectedBinId())
                || !Objects.equals(old.newBin(),body.destinationBinId())
                || !old.reason().equals(explanation))
                throw conflict("Move request key used for a different action");
            return pieces(tenant,receipt);
        }
        lockPendingBatch(tenant,receipt);
        Piece source=piece(tenant,body.pieceId());
        if(!receipt.toString().equals(source.receiptId()))throw missing();
        if(!source.binId().equals(body.expectedBinId().toString()))
            throw conflict("Lot moved already; refresh expected location");
        requireQcBin(tenant,UUID.fromString(source.warehouseId()),body.destinationBinId());
        int changed=jdbc.update("""
            UPDATE erp_receipt_lot_piece SET bin_id=?,updated_at=now()
            WHERE tenant_id=? AND id=? AND bin_id=?
            """,body.destinationBinId(),tenant,body.pieceId(),body.expectedBinId());
        if(changed!=1)throw conflict("Source location changed");
        event(tenant,actor,receipt,body.pieceId(),null,body.requestKey(),
            "MOVED",source.quantity(),body.expectedBinId(),body.destinationBinId(),
            explanation);
        return pieces(tenant,receipt);
    }

    @Transactional(readOnly=true)
    public List<Pressure> pressure(UUID tenant,UUID warehouse){
        // Deliberately a supply-visibility view, NOT an automatic order or
        // an instruction to count held material as available inventory.
        return jdbc.query("""
            SELECT b.warehouse_id,b.product_sku_id,s.internal_sku,
              COALESCE((SELECT SUM(m.quantity) FROM erp_local_stock_movement m
                WHERE m.tenant_id=b.tenant_id AND m.warehouse_id=b.warehouse_id
                  AND m.product_sku_id=b.product_sku_id),0)::bigint AS usable_units,
              SUM(CASE WHEN b.status='PENDING_QC'
                THEN b.received_quantity ELSE 0 END)::bigint AS pending_units,
              SUM(CASE WHEN b.status='INSPECTED'
                THEN q.accepted_quantity ELSE 0 END)::bigint AS awaiting_units,
              COALESCE((SELECT SUM(a.quantity - COALESCE((
                SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                WHERE r.tenant_id=a.tenant_id AND r.asn_id=a.id),0))
                FROM erp_purchase_asn a
                JOIN erp_purchase_order_line l
                  ON l.tenant_id=a.tenant_id AND l.id=a.purchase_order_line_id
                JOIN erp_purchase_order o
                  ON o.tenant_id=a.tenant_id AND o.id=a.purchase_order_id
                WHERE a.tenant_id=b.tenant_id AND a.warehouse_id=b.warehouse_id
                  AND l.product_sku_id=b.product_sku_id AND a.status='SHIPPED'
                  AND o.status IN ('APPROVED','RECEIVING')),0)::bigint AS inbound_units
            FROM erp_receipt_quality_batch b
            JOIN product_sku s ON s.tenant_id=b.tenant_id AND s.id=b.product_sku_id
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
            WHERE b.tenant_id=? AND b.warehouse_id=?
            GROUP BY b.tenant_id,b.warehouse_id,b.product_sku_id,s.internal_sku
            ORDER BY s.internal_sku LIMIT 300
            """,(rs,i)->new Pressure(rs.getObject("warehouse_id",UUID.class).toString(),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getString("internal_sku"),rs.getLong("usable_units"),
                rs.getLong("pending_units"),rs.getLong("awaiting_units"),
                rs.getLong("inbound_units")),tenant,warehouse);
    }
}
