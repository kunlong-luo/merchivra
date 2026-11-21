package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Released-lot location allocations are an exact subset of signed usable stock.
 * FIFO/FEFO pick consumes location and warehouse OUTBOUND in the SAME transaction.
 */
@Service
public class ReleasedLotStockService {
    private final JdbcTemplate jdbc;
    private final WarehouseStockOperationService stock;
    public ReleasedLotStockService(JdbcTemplate jdbc,WarehouseStockOperationService stock) {
        this.jdbc=jdbc;this.stock=stock;
    }
    public record PutawayRequest(UUID requestKey,UUID receiptId,UUID binId,int quantity,
                                 LocalDate expiryDate,String reason) {}
    public record Position(String id,String receiptId,String warehouseId,String productSkuId,
                           String binId,String binCode,String lotCode,int placedQuantity,
                           int remainingQuantity,String expiryDate,String createdAt) {}
    public record PickRequest(UUID requestKey,UUID warehouseId,UUID productSkuId,
                              String policy,int quantity,long expectedOnHand,String reason) {}
    public record ReceiptAllocation(String receiptId,String warehouseId,String productSkuId,
                                    String status,int acceptedQuantity,long placedQuantity,
                                    long remainingQuantity) {}
    public record PickLine(String positionId,String lotCode,String binCode,int quantity,
                           String expiryDate) {}
    public record Pick(String id,String warehouseId,String productSkuId,String operationId,
                       String policy,int quantity,long expectedOnHand,String reason,
                       String actorUserId,List<PickLine> allocations) {}
    private record Source(String warehouse,String sku,String status,int accepted) {}
    private static ResponseStatusException bad(String m){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);
    }
    private static ResponseStatusException conflict(String m){
        return new ResponseStatusException(HttpStatus.CONFLICT,m);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Released lot source unavailable");
    }
    private static String reason(String v) {
        if(v==null || v.strip().length()<5 || v.strip().length()>300
            || v.chars().anyMatch(Character::isISOControl))
            throw bad("Reason must contain 5..300 printable characters");
        return v.strip();
    }
    private void lockTenant(UUID tenant) {
        List<UUID> x=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(x.size()!=1)throw missing();
    }
    private Source source(UUID tenant,UUID receipt) {
        return jdbc.query("""
            SELECT b.warehouse_id,b.product_sku_id,b.status,
              COALESCE(q.accepted_quantity,0)::integer AS accepted
            FROM erp_receipt_quality_batch b
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
            WHERE b.tenant_id=? AND b.receipt_id=? FOR UPDATE OF b
            """,(rs,i)->new Source(rs.getObject("warehouse_id",UUID.class).toString(),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getString("status"),rs.getInt("accepted")),
            tenant,receipt).stream().findFirst().orElseThrow(ReleasedLotStockService::missing);
    }
    private static Position mapPosition(ResultSet rs,int i) throws SQLException{
        LocalDate expiry=rs.getObject("expiry_date",LocalDate.class);
        return new Position(rs.getObject("id",UUID.class).toString(),
            rs.getObject("receipt_id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getObject("bin_id",UUID.class).toString(),rs.getString("bin_code"),
            rs.getString("lot_code"),rs.getInt("placed_quantity"),
            rs.getInt("remaining_quantity"),expiry==null?null:expiry.toString(),
            rs.getObject("created_at",OffsetDateTime.class).toInstant().toString());
    }
    private static final String POSITION_SQL = """
        SELECT p.id,p.receipt_id,p.warehouse_id,p.product_sku_id,
          p.bin_id,z.bin_code,b.lot_code,p.placed_quantity,
          p.remaining_quantity,p.expiry_date,p.created_at
        FROM erp_local_lot_position p
        JOIN erp_local_stock_bin z
          ON z.tenant_id=p.tenant_id AND z.id=p.bin_id
        JOIN erp_receipt_quality_batch b
          ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
        WHERE p.tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Position> positions(UUID tenant,UUID warehouse) {
        return jdbc.query(POSITION_SQL+"""
            AND p.warehouse_id=? ORDER BY p.created_at DESC,p.id DESC LIMIT 300
            """,ReleasedLotStockService::mapPosition,tenant,warehouse);
    }
    /** V133: keyset source directory; legacy /positions is 300-row compatible. */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Position> positionsPage(UUID tenant,UUID warehouse,
        int requested,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,4);
        if(after!=null && (!tenant.toString().equals(after[0])
            || !warehouse.toString().equals(after[1])))
            throw bad("Lot position cursor belongs to another tenant or warehouse");
        var args=new ArrayList<Object>();
        args.add(tenant);
        args.add(warehouse);
        String predicate="";
        if(after!=null){
            try{
                predicate=" AND (p.created_at,p.id) < (?,?)";
                args.add(OffsetDateTime.ofInstant(Instant.parse(after[2]),ZoneOffset.UTC));
                args.add(UUID.fromString(after[3]));
            }catch(java.time.DateTimeException | IllegalArgumentException ex){
                throw bad("Invalid lot position cursor");
            }
        }
        args.add(limit+1);
        // V136: limit the indexed position directory BEFORE joining receipt
        // and bin detail. Otherwise PG18 may scan/sort all positions of one
        // large tenant+warehouse before applying the 51-row lookahead.
        List<Position> rows=jdbc.query("""
            WITH selected AS MATERIALIZED (
              SELECT p.id,p.tenant_id,p.receipt_id,p.warehouse_id,p.product_sku_id,
                p.bin_id,p.placed_quantity,p.remaining_quantity,p.expiry_date,p.created_at
              FROM erp_local_lot_position p
              WHERE p.tenant_id=? AND p.warehouse_id=?
            """+predicate+"""
              ORDER BY p.created_at DESC,p.id DESC LIMIT ?
            )
            SELECT p.id,p.receipt_id,p.warehouse_id,p.product_sku_id,
              p.bin_id,z.bin_code,b.lot_code,p.placed_quantity,
              p.remaining_quantity,p.expiry_date,p.created_at
            FROM selected p
            JOIN erp_local_stock_bin z
              ON z.tenant_id=p.tenant_id AND z.id=p.bin_id
            JOIN erp_receipt_quality_batch b
              ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
            ORDER BY p.created_at DESC,p.id DESC
            """,ReleasedLotStockService::mapPosition,args.toArray());
        return LocalKeysetPage.pack(rows,limit,r->LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),r.createdAt(),r.id()));
    }

    /** V133: source-truth allocation across ALL original receipt positions,
     * excluding transferred descendants. Never infer capacity from a page.
     */
    @Transactional(readOnly=true)
    public ReceiptAllocation receiptAllocation(UUID tenant,UUID receiptId){
        if(receiptId==null)throw bad("Real receipt required");
        return jdbc.query("""
            SELECT b.receipt_id,b.warehouse_id,b.product_sku_id,b.status,
              COALESCE(q.accepted_quantity,0)::integer AS accepted,
              COALESCE((
                SELECT SUM(p.placed_quantity) FROM erp_local_lot_position p
                WHERE p.tenant_id=b.tenant_id AND p.receipt_id=b.receipt_id
                  AND p.origin_position_id IS NULL
              ),0)::bigint AS placed
            FROM erp_receipt_quality_batch b
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
            WHERE b.tenant_id=? AND b.receipt_id=?
            """,(rs,i)->{
                int accepted=rs.getInt("accepted");
                long placed=rs.getLong("placed");
                String status=rs.getString("status");
                if(placed>accepted && "RELEASED".equals(status))
                    throw conflict("Released receipt allocation exceeds independently approved quantity");
                long available="RELEASED".equals(status)?Math.max(0,(long)accepted-placed):0;
                return new ReceiptAllocation(rs.getObject("receipt_id",UUID.class).toString(),
                    rs.getObject("warehouse_id",UUID.class).toString(),
                    rs.getObject("product_sku_id",UUID.class).toString(),
                    status,accepted,placed,available);
            },tenant,receiptId).stream().findFirst().orElseThrow(ReleasedLotStockService::missing);
    }
    private Position position(UUID tenant,UUID id) {
        return jdbc.query(POSITION_SQL+" AND p.id=?",
            ReleasedLotStockService::mapPosition,tenant,id)
            .stream().findFirst().orElseThrow(ReleasedLotStockService::missing);
    }
    private long remaining(UUID tenant,UUID warehouse,UUID sku) {
        Long count=jdbc.queryForObject("""
            SELECT COALESCE(SUM(remaining_quantity),0)::bigint
            FROM erp_local_lot_position
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return count==null?0:count;
    }
    private long balance(UUID tenant,UUID warehouse,UUID sku) {
        Long count=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_local_stock_movement
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return count==null?0:count;
    }
    private void lockWarehouse(UUID tenant,UUID warehouse) {
        List<UUID> ids=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant,warehouse);
        if(ids.size()!=1)throw missing();
    }
    @Transactional
    public Position putaway(UUID tenant,UUID actor,PutawayRequest req) {
        if(req==null || req.requestKey()==null || req.receiptId()==null
            || req.binId()==null || req.quantity()<1 || req.quantity()>1_000_000)
            throw bad("Invalid released-lot putaway request");
        String detail=reason(req.reason());
        lockTenant(tenant);
        List<UUID> keys=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_position WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!keys.isEmpty()) {
            Position saved=position(tenant,keys.getFirst());
            if(!saved.receiptId().equals(req.receiptId().toString())
               || !saved.binId().equals(req.binId().toString())
               || saved.placedQuantity()!=req.quantity()
               || !java.util.Objects.equals(saved.expiryDate(),
                    req.expiryDate()==null?null:req.expiryDate().toString()))
                throw conflict("Putaway request key already used with different source");
            List<String> provenance=jdbc.query("""
                SELECT reason,created_by FROM erp_local_lot_position
                WHERE tenant_id=? AND id=?
                """,(rs,i)->rs.getString("reason")+"|"+rs.getObject("created_by",UUID.class),
                tenant,keys.getFirst());
            if(provenance.size()!=1 || !provenance.getFirst().equals(detail+"|"+actor))
                throw conflict("Putaway retry must preserve reason and submitting Owner");
            return saved;
        }
        Source src=source(tenant,req.receiptId());
        if(!"RELEASED".equals(src.status()) || src.accepted()<1)
            throw conflict("Only approved, accepted QC goods may enter STORAGE");
        UUID warehouse=UUID.fromString(src.warehouse());
        UUID sku=UUID.fromString(src.sku());
        lockWarehouse(tenant,warehouse);
        List<UUID> availableBins=jdbc.queryForList("""
            SELECT id FROM erp_local_stock_bin
            WHERE tenant_id=? AND warehouse_id=? AND id=?
              AND purpose='STORAGE' AND status='ACTIVE'
            """,UUID.class,tenant,warehouse,req.binId());
        if(availableBins.size()!=1)throw missing();
        Long allocated=jdbc.queryForObject("""
            SELECT COALESCE(SUM(placed_quantity),0)::bigint
            FROM erp_local_lot_position WHERE tenant_id=? AND receipt_id=?
              AND origin_position_id IS NULL
            """,Long.class,tenant,req.receiptId());
        if((allocated==null?0:allocated)+req.quantity()>src.accepted())
            throw conflict("Putaway quantity exceeds this independently released QC batch");
        if(remaining(tenant,warehouse,sku)+req.quantity()>balance(tenant,warehouse,sku))
            throw conflict("Insufficient unpositioned warehouse stock for a new lot placement");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_position
              (id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,
               bin_id,placed_quantity,remaining_quantity,expiry_date,reason,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),req.receiptId(),warehouse,sku,req.binId(),
            req.quantity(),req.quantity(),req.expiryDate(),detail,actor);
        jdbc.update("""
            INSERT INTO erp_local_lot_event
              (id,tenant_id,position_id,event_type,quantity,actor_user_id)
            VALUES (?,?,?,'PUTAWAY',?,?)
            """,UUID.randomUUID(),tenant,id,req.quantity(),actor);
        return position(tenant,id);
    }
    private List<Position> candidates(UUID tenant,UUID warehouse,UUID sku,
                                      String policy,int rowLimit){
        // V130: each eligible position has >=1 unit. An actual pick of Q units
        // therefore needs at most Q positions; a fixed LIMIT 500 could falsely
        // reject a fully stocked SKU split across >500 real lots. Preview is
        // still intentionally bounded and never promises a full stock total.
        return jdbc.query(POSITION_SQL+"""
            AND p.warehouse_id=? AND p.product_sku_id=?
            AND p.remaining_quantity>0
            AND (p.expiry_date IS NULL OR p.expiry_date>=CURRENT_DATE)
            ORDER BY CASE WHEN ?='FEFO' THEN p.expiry_date END ASC NULLS LAST,
              b.created_at ASC,p.created_at ASC,p.id ASC
            LIMIT ?
            """,ReleasedLotStockService::mapPosition,tenant,warehouse,sku,
            policy,rowLimit);
    }
    private static String policy(String p) {
        if(p==null || !List.of("FIFO","FEFO").contains(p))
            throw bad("Pick policy must be FIFO or FEFO");
        return p;
    }
    @Transactional(readOnly=true)
    public List<Position> preview(UUID tenant,UUID warehouse,UUID sku,String p){
        if(warehouse==null || sku==null)throw bad("Warehouse and SKU required");
        return candidates(tenant,warehouse,sku,policy(p),500);
    }
    private Pick byPick(UUID tenant,UUID id){
        List<Pick> result=jdbc.query("""
            SELECT id,warehouse_id,product_sku_id,operation_id,policy,
              quantity,expected_on_hand,reason,actor_user_id
            FROM erp_local_lot_pick WHERE tenant_id=? AND id=?
            """,(rs,i)->new Pick(rs.getObject("id",UUID.class).toString(),
                rs.getObject("warehouse_id",UUID.class).toString(),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getObject("operation_id",UUID.class).toString(),
                rs.getString("policy"),rs.getInt("quantity"),
                rs.getLong("expected_on_hand"),rs.getString("reason"),
                rs.getObject("actor_user_id",UUID.class).toString(),List.of()),
                tenant,id);
        if(result.isEmpty())throw missing();
        Pick x=result.getFirst();
        List<PickLine> lines=jdbc.query("""
            SELECT l.position_id,b.lot_code,z.bin_code,l.quantity,p.expiry_date
            FROM erp_local_lot_pick_line l
            JOIN erp_local_lot_position p
              ON p.tenant_id=l.tenant_id AND p.id=l.position_id
            JOIN erp_receipt_quality_batch b
              ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
            JOIN erp_local_stock_bin z
              ON z.tenant_id=p.tenant_id AND z.id=p.bin_id
            WHERE l.tenant_id=? AND l.pick_id=?
            ORDER BY l.id
            """,(rs,i)->{
                LocalDate expiry=rs.getObject("expiry_date",LocalDate.class);
                return new PickLine(rs.getObject("position_id",UUID.class).toString(),
                    rs.getString("lot_code"),rs.getString("bin_code"),
                    rs.getInt("quantity"),expiry==null?null:expiry.toString());
            },tenant,id);
        return new Pick(x.id(),x.warehouseId(),x.productSkuId(),x.operationId(),
            x.policy(),x.quantity(),x.expectedOnHand(),x.reason(),
            x.actorUserId(),lines);
    }
    @Transactional
    public Pick pick(UUID tenant,UUID actor,PickRequest req) {
        if(req==null || req.requestKey()==null || req.warehouseId()==null
           || req.productSkuId()==null || req.quantity()<1 || req.quantity()>1_000_000
           || req.expectedOnHand()<0)
            throw bad("Missing or invalid lot pick input");
        String p=policy(req.policy());
        String detail=reason(req.reason());
        lockTenant(tenant);
        List<UUID> previous=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_pick WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!previous.isEmpty()){
            Pick saved=byPick(tenant,previous.getFirst());
            if(!saved.actorUserId().equals(actor.toString())
                || !saved.warehouseId().equals(req.warehouseId().toString())
                || !saved.productSkuId().equals(req.productSkuId().toString())
                || !saved.policy().equals(p) || saved.quantity()!=req.quantity()
                || saved.expectedOnHand()!=req.expectedOnHand()
                || !saved.reason().equals(detail))
                throw conflict("Lot pick request key already used differently");
            return saved;
        }
        lockWarehouse(tenant,req.warehouseId());
        long balance=balance(tenant,req.warehouseId(),req.productSkuId());
        if(balance!=req.expectedOnHand())
            throw conflict("Warehouse balance changed; refresh before lot pick");
        if(balance<req.quantity())throw conflict("Insufficient usable local stock");
        List<Position> sorted=candidates(tenant,req.warehouseId(),
            req.productSkuId(),p,req.quantity());
        int needed=req.quantity();
        record Allocation(UUID id,int quantity) {}
        List<Allocation> consumed=new ArrayList<>();
        for(Position item:sorted) {
            if(needed==0)break;
            int count=Math.min(needed,item.remainingQuantity());
            consumed.add(new Allocation(UUID.fromString(item.id()),count));
            needed-=count;
        }
        if(needed>0)
            throw conflict("Insufficient non-expired positioned lots; unlocated units require regular outbound");
        // First reserve position quantities: V40 generic outbound guard sees
        // only the residual positioned quantity after this exact allocation.
        for(Allocation item:consumed){
            int changed=jdbc.update("""
                UPDATE erp_local_lot_position
                SET remaining_quantity=remaining_quantity-?
                WHERE tenant_id=? AND id=? AND remaining_quantity>=?
                """,item.quantity(),tenant,item.id(),item.quantity());
            if(changed!=1)throw conflict("Lot position changed during pick");
        }
        WarehouseStockOperationService.Request move=
            new WarehouseStockOperationService.Request(req.requestKey(),"OUTBOUND",
                req.warehouseId(),null,req.productSkuId(),req.quantity(),
                req.expectedOnHand(),detail);
        WarehouseStockOperationService.Operation posted=stock.post(tenant,actor,move);
        UUID pickId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_pick
              (id,tenant_id,request_key,warehouse_id,product_sku_id,
               operation_id,policy,quantity,expected_on_hand,reason,actor_user_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,pickId,tenant,req.requestKey(),req.warehouseId(),req.productSkuId(),
            UUID.fromString(posted.id()),p,req.quantity(),req.expectedOnHand(),
            detail,actor);
        for(Allocation item:consumed){
            jdbc.update("""
                INSERT INTO erp_local_lot_pick_line
                  (id,tenant_id,pick_id,position_id,quantity) VALUES (?,?,?,?,?)
                """,UUID.randomUUID(),tenant,pickId,item.id(),item.quantity());
            jdbc.update("""
                INSERT INTO erp_local_lot_event
                  (id,tenant_id,position_id,pick_id,event_type,quantity,actor_user_id)
                VALUES (?,?,?,?,'PICKED',?,?)
                """,UUID.randomUUID(),tenant,item.id(),pickId,item.quantity(),actor);
        }
        return byPick(tenant,pickId);
    }
}
