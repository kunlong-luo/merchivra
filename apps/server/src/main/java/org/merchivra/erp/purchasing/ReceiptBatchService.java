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

/**
 * New V46 physical receipts are pending quality inspection, not usable stock.
 * Only a separate Owner (not the QC inspector) may release accepted units.
 */
@Service
public class ReceiptBatchService {
    private final JdbcTemplate jdbc;
    public ReceiptBatchService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Batch(String receiptId,String warehouseId,String productSkuId,
                        String lotCode,int receivedQuantity,String status,
                        String inspectionId,int acceptedQuantity,int rejectedQuantity,
                        String inspectedBy,String releasedBy,String createdAt) {}
    public record LotChange(String expectedLotCode,String newLotCode,String reason) {}
    public record Event(String id,String eventType,String actorUserId,String oldLotCode,
                        String newLotCode,Integer quantity,String note,String createdAt) {}
    /** Full-order totals are not the visible 50-row page subtotal. */
    public record BatchSummary(long pendingReceivedUnits,long awaitingReleaseAcceptedUnits,
                               long pendingBatchCount,long awaitingReleaseBatchCount) {}
    private static ResponseStatusException bad(String msg){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);
    }
    private static ResponseStatusException conflict(String msg){
        return new ResponseStatusException(HttpStatus.CONFLICT,msg);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending receipt lot unavailable");
    }
    private static String instant(ResultSet rs,String name) throws SQLException {
        OffsetDateTime value=rs.getObject(name,OffsetDateTime.class);
        return value==null?null:value.toInstant().toString();
    }
    private static String safeLot(String value){
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{1,79}"))
            throw bad("Batch/lot code must contain 2..80 safe characters");
        return value;
    }
    private static String note(String raw) {
        if(raw==null || raw.strip().length()<5 || raw.strip().length()>300 ||
            raw.chars().anyMatch(Character::isISOControl))
            throw bad("Lot change reason must contain 5..300 printable characters");
        return raw.strip();
    }
    private static Batch mapBatch(ResultSet rs,int i) throws SQLException {
        UUID qc=rs.getObject("inspection_id",UUID.class);
        UUID inspector=rs.getObject("inspected_by",UUID.class);
        UUID releaser=rs.getObject("released_by",UUID.class);
        return new Batch(rs.getObject("receipt_id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getString("lot_code"),rs.getInt("received_quantity"),rs.getString("status"),
            qc==null?null:qc.toString(),rs.getInt("accepted_quantity"),
            rs.getInt("rejected_quantity"),
            inspector==null?null:inspector.toString(),
            releaser==null?null:releaser.toString(),instant(rs,"created_at"));
    }
    private static final String BATCH_SQL = """
        SELECT b.receipt_id,b.warehouse_id,b.product_sku_id,b.lot_code,
          b.received_quantity,b.status,b.inspection_id,b.released_by,b.created_at,
          COALESCE(q.accepted_quantity,0)::integer AS accepted_quantity,
          COALESCE(q.rejected_quantity,0)::integer AS rejected_quantity,
          q.inspected_by
        FROM erp_receipt_quality_batch b
        LEFT JOIN erp_purchase_receipt_qc q
          ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
        WHERE b.tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Batch> forOrder(UUID tenant,UUID order) {
        return jdbc.query(BATCH_SQL+"""
            AND b.receipt_id IN (
              SELECT r.id FROM erp_purchase_receipt r
              WHERE r.tenant_id=? AND r.purchase_order_id=?
            ) ORDER BY b.created_at DESC,b.receipt_id DESC LIMIT 300
            """,ReceiptBatchService::mapBatch,tenant,tenant,order);
    }
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Batch> forOrderPage(UUID tenant,UUID order,
                                                     int requested,String cursor) {
        if(order==null)throw bad("Purchase order required");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,3);
        if(after!=null && !order.toString().equals(after[0]))
            throw bad("Receipt batch cursor belongs to a different purchase order");
        var args=new java.util.ArrayList<Object>();
        args.add(tenant);args.add(tenant);args.add(order);
        String where="";
        if(after!=null) {
            try {
                where=" AND (b.created_at,b.receipt_id) < (?,?)";
                args.add(OffsetDateTime.ofInstant(java.time.Instant.parse(after[1]),
                    java.time.ZoneOffset.UTC));
                args.add(UUID.fromString(after[2]));
            } catch(Exception e) {throw bad("Invalid receipt batch cursor");}
        }
        args.add(limit+1);
        List<Batch> rows=jdbc.query(BATCH_SQL+"""
            AND b.receipt_id IN (
              SELECT r.id FROM erp_purchase_receipt r
              WHERE r.tenant_id=? AND r.purchase_order_id=?
            )
            """+where+" ORDER BY b.created_at DESC,b.receipt_id DESC LIMIT ?",
            ReceiptBatchService::mapBatch,args.toArray());
        return LocalKeysetPage.pack(rows,limit,b->LocalKeysetPage.encode(
            order.toString(),b.createdAt(),b.receiptId()));
    }

    @Transactional(readOnly=true)
    public BatchSummary forOrderSummary(UUID tenant,UUID order) {
        if(order==null)throw bad("Purchase order required");
        return jdbc.query("""
            SELECT COALESCE(SUM(CASE WHEN b.status='PENDING_QC'
                     THEN b.received_quantity ELSE 0 END),0)::bigint AS pending_units,
                   COALESCE(SUM(CASE WHEN b.status='INSPECTED'
                     THEN q.accepted_quantity ELSE 0 END),0)::bigint AS awaiting_units,
                   COUNT(*) FILTER (WHERE b.status='PENDING_QC') AS pending_count,
                   COUNT(*) FILTER (WHERE b.status='INSPECTED') AS awaiting_count
            FROM erp_receipt_quality_batch b
            JOIN erp_purchase_receipt r ON r.tenant_id=b.tenant_id AND r.id=b.receipt_id
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
            WHERE b.tenant_id=? AND r.purchase_order_id=?
            """,(rs,i)->new BatchSummary(rs.getLong("pending_units"),
              rs.getLong("awaiting_units"),rs.getLong("pending_count"),
              rs.getLong("awaiting_count")),tenant,order).getFirst();
    }

    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Batch> forWarehousePage(UUID tenant,UUID warehouse,
                                                         int requested,String cursor) {
        if(warehouse==null)throw bad("Warehouse required");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,3);
        if(after!=null && !warehouse.toString().equals(after[0]))
            throw bad("Receipt batch cursor belongs to a different warehouse");
        var args=new java.util.ArrayList<Object>();
        args.add(tenant);args.add(warehouse);
        String where="";
        if(after!=null) {
            try {
                where=" AND (b.created_at,b.receipt_id) < (?,?)";
                args.add(OffsetDateTime.ofInstant(java.time.Instant.parse(after[1]),
                    java.time.ZoneOffset.UTC));
                args.add(UUID.fromString(after[2]));
            } catch(Exception e) {throw bad("Invalid receipt batch cursor");}
        }
        args.add(limit+1);
        List<Batch> rows=jdbc.query(BATCH_SQL+
            " AND b.warehouse_id=?"+where+
            " ORDER BY b.created_at DESC,b.receipt_id DESC LIMIT ?",
            ReceiptBatchService::mapBatch,args.toArray());
        return LocalKeysetPage.pack(rows,limit,b->LocalKeysetPage.encode(
            warehouse.toString(),b.createdAt(),b.receiptId()));
    }

    @Transactional(readOnly=true)
    public List<Batch> forWarehouse(UUID tenant,UUID warehouse) {
        return jdbc.query(BATCH_SQL+"""
            AND b.warehouse_id=? ORDER BY b.created_at DESC,b.receipt_id DESC LIMIT 300
            """,ReceiptBatchService::mapBatch,tenant,warehouse);
    }
    private Batch byId(UUID tenant,UUID receiptId){
        return jdbc.query(BATCH_SQL+" AND b.receipt_id=?",
            ReceiptBatchService::mapBatch,tenant,receiptId)
            .stream().findFirst().orElseThrow(ReceiptBatchService::missing);
    }
    private void tenantLock(UUID tenant) {
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private void event(UUID tenant,UUID actor,UUID receipt,String type,String oldLot,
                       String newLot,Integer quantity,String detail){
        jdbc.update("""
            INSERT INTO erp_receipt_quality_batch_event
              (id,tenant_id,receipt_id,actor_user_id,event_type,
               old_lot_code,new_lot_code,quantity,note)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,receipt,actor,type,oldLot,newLot,quantity,detail);
    }
    @Transactional
    public Batch relabel(UUID tenant,UUID actor,UUID receiptId,LotChange request){
        if(receiptId==null || request==null)throw bad("Receipt lot and version required");
        String oldLot=safeLot(request.expectedLotCode());
        String newLot=safeLot(request.newLotCode());
        String reason=note(request.reason());
        tenantLock(tenant);
        List<String> existing=jdbc.query("""
            SELECT lot_code FROM erp_receipt_quality_batch
            WHERE tenant_id=? AND receipt_id=? AND status='PENDING_QC' FOR UPDATE
            """,(rs,i)->rs.getString(1),tenant,receiptId);
        if(existing.size()!=1)throw conflict("Only uninspected receipt lot can be relabeled");
        if(!existing.getFirst().equals(oldLot))
            throw conflict("Lot code changed, refresh before editing");
        if(oldLot.equals(newLot))return byId(tenant,receiptId);
        jdbc.update("""
            UPDATE erp_receipt_quality_batch SET lot_code=?,updated_at=now()
            WHERE tenant_id=? AND receipt_id=? AND status='PENDING_QC'
            """,newLot,tenant,receiptId);
        event(tenant,actor,receiptId,"LOT_RELABELED",oldLot,newLot,null,reason);
        return byId(tenant,receiptId);
    }
    @Transactional
    public Batch release(UUID tenant,UUID actor,UUID receiptId) {
        if(receiptId==null)throw bad("Receipt required");
        tenantLock(tenant);
        List<UUID> found=jdbc.queryForList("""
            SELECT receipt_id FROM erp_receipt_quality_batch
            WHERE tenant_id=? AND receipt_id=? FOR UPDATE
            """,UUID.class,tenant,receiptId);
        if(found.size()!=1)throw missing();
        Batch batch=byId(tenant,receiptId);
        if("RELEASED".equals(batch.status()))return batch;
        if(!"INSPECTED".equals(batch.status()) || batch.inspectionId()==null)
            throw conflict("Receipt must pass recorded QC before available-stock release");
        if(actor.toString().equals(batch.inspectedBy()))
            throw conflict("The inspecting Owner cannot approve the same lot for release");
        // Same warehouse lock as V40 inventory operations; this movement is the
        // first available-stock addition for V46 receipts, never for legacy V44.
        List<UUID> warehouse=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant,UUID.fromString(batch.warehouseId()));
        if(warehouse.size()!=1)throw missing();
        if(batch.acceptedQuantity()>0)jdbc.update("""
            INSERT INTO erp_local_stock_movement
              (id,tenant_id,warehouse_id,product_sku_id,inspection_id,
               movement_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,'QC_RELEASE',?,?)
            """,UUID.randomUUID(),tenant,UUID.fromString(batch.warehouseId()),
            UUID.fromString(batch.productSkuId()),UUID.fromString(batch.inspectionId()),
            batch.acceptedQuantity(),actor);
        jdbc.update("""
            UPDATE erp_receipt_quality_batch
            SET status='RELEASED',released_by=?,released_at=now(),updated_at=now()
            WHERE tenant_id=? AND receipt_id=? AND status='INSPECTED'
            """,actor,tenant,receiptId);
        event(tenant,actor,receiptId,"QC_RELEASED",null,null,
            batch.acceptedQuantity(),"Independent Owner released inspected accepted units");
        return byId(tenant,receiptId);
    }
    private static Event mapEvent(ResultSet rs,int i) throws SQLException {
        UUID actor=rs.getObject("actor_user_id",UUID.class);
        int quantity=rs.getInt("quantity");
        boolean noQuantity=rs.wasNull();
        return new Event(rs.getObject("id",UUID.class).toString(),
            rs.getString("event_type"),actor.toString(),
            rs.getString("old_lot_code"),rs.getString("new_lot_code"),
            noQuantity?null:quantity,rs.getString("note"),instant(rs,"created_at"));
    }
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Event> eventsPage(UUID tenant,UUID receiptId,
                                                  int requested,String cursor) {
        if(receiptId==null)throw bad("Receipt is required");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,3);
        if(after!=null && !receiptId.toString().equals(after[0]))
            throw bad("Receipt audit cursor belongs to a different receipt");
        byId(tenant,receiptId);
        var args=new java.util.ArrayList<Object>();
        args.add(tenant);args.add(receiptId);
        String where="";
        if(after!=null) {
            try {
                where=" AND (created_at,id) < (?,?)";
                args.add(OffsetDateTime.ofInstant(java.time.Instant.parse(after[1]),
                    java.time.ZoneOffset.UTC));
                args.add(UUID.fromString(after[2]));
            } catch(Exception e) {throw bad("Invalid receipt audit cursor");}
        }
        args.add(limit+1);
        List<Event> rows=jdbc.query("""
            SELECT id,event_type,actor_user_id,old_lot_code,new_lot_code,
                   quantity,note,created_at
            FROM erp_receipt_quality_batch_event
            WHERE tenant_id=? AND receipt_id=?
            """+where+" ORDER BY created_at DESC,id DESC LIMIT ?",
            ReceiptBatchService::mapEvent,args.toArray());
        return LocalKeysetPage.pack(rows,limit,e->LocalKeysetPage.encode(
            receiptId.toString(),e.createdAt(),e.id()));
    }

    @Transactional(readOnly=true)
    public List<Event> events(UUID tenant,UUID receiptId) {
        byId(tenant,receiptId); // prove tenant ownership before reading audit
        return jdbc.query("""
            SELECT id,event_type,actor_user_id,old_lot_code,new_lot_code,
              quantity,note,created_at
            FROM erp_receipt_quality_batch_event
            WHERE tenant_id=? AND receipt_id=?
            ORDER BY created_at DESC,id DESC LIMIT 200
            """,ReceiptBatchService::mapEvent,tenant,receiptId);
    }
}
