package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V49: track real released-lot position changes, source-bound transfers,
 * human expiry quarantine and nonposting physical count observations.
 * Tenant and warehouse locks serialize these actions with V40 and V48.
 */
@Service
public class ReleasedLotLifecycleService {
    private final JdbcTemplate jdbc;
    private final WarehouseStockOperationService stock;
    public ReleasedLotLifecycleService(JdbcTemplate jdbc,WarehouseStockOperationService stock) {
        this.jdbc=jdbc;this.stock=stock;
    }
    public record Request(UUID requestKey,String actionType,UUID expectedBinId,
                          UUID destinationBinId,int quantity,int expectedRemaining,Long expectedOnHand,
                          Integer observedQuantity,String reason) {}
    public record Action(String id,String actionType,String sourcePositionId,
                         String sourceWarehouseId,String sourceBinId,
                         String destinationWarehouseId,String destinationBinId,
                         String destinationPositionId,String operationId,
                         int quantity,int expectedRemaining,Long expectedOnHand,
                         Integer observedQuantity,String reason,String actorUserId,
                         String createdAt) {}
    public record ExpiryAlert(String positionId,String lotCode,String binCode,
                              String productSkuId,int remainingQuantity,
                              String expiryDate,String urgency) {}
    public record Lineage(String positionId,String originPositionId,String receiptId,
                          String warehouseId,String binCode,String lotCode,
                          int placedQuantity,int remainingQuantity,
                          String expiryDate,int generation) {}
    private record Source(UUID id,UUID receipt,UUID warehouse,UUID sku,UUID bin,
                          int remaining,LocalDate expiry) {}
    private static ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);
    }
    private static ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT,text);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Released lot or bin unavailable");
    }
    private static String reason(String raw) {
        if(raw==null || raw.strip().length()<5 || raw.strip().length()>300 ||
            raw.chars().anyMatch(Character::isISOControl))
            throw bad("Action reason must contain 5..300 printable characters");
        return raw.strip();
    }
    private static Action map(ResultSet rs,int index) throws SQLException {
        UUID destWh=rs.getObject("destination_warehouse_id",UUID.class);
        UUID destBin=rs.getObject("destination_bin_id",UUID.class);
        UUID target=rs.getObject("destination_position_id",UUID.class);
        UUID operation=rs.getObject("operation_id",UUID.class);
        long expectedStock=rs.getLong("expected_on_hand");
        boolean noExpected=rs.wasNull();
        int counted=rs.getInt("observed_quantity");
        boolean noCount=rs.wasNull();
        return new Action(rs.getObject("id",UUID.class).toString(),rs.getString("action_type"),
            rs.getObject("source_position_id",UUID.class).toString(),
            rs.getObject("source_warehouse_id",UUID.class).toString(),
            rs.getObject("source_bin_id",UUID.class).toString(),
            destWh==null?null:destWh.toString(),
            destBin==null?null:destBin.toString(),
            target==null?null:target.toString(),
            operation==null?null:operation.toString(),
            rs.getInt("quantity"),rs.getInt("expected_remaining"),
            noExpected?null:expectedStock,noCount?null:counted,
            rs.getString("reason"),
            rs.getObject("actor_user_id",UUID.class).toString(),
            rs.getObject("created_at",OffsetDateTime.class).toInstant().toString());
    }
    private static final String ACTION_SQL = """
        SELECT id,action_type,source_position_id,source_warehouse_id,source_bin_id,
          destination_warehouse_id,destination_bin_id,destination_position_id,operation_id,
          quantity,expected_remaining,expected_on_hand,observed_quantity,
          reason,actor_user_id,created_at
        FROM erp_local_lot_action WHERE tenant_id=?
        """;
    private Action get(UUID tenant,UUID id) {
        return jdbc.query(ACTION_SQL+" AND id=?",
            ReleasedLotLifecycleService::map,tenant,id)
            .stream().findFirst().orElseThrow(ReleasedLotLifecycleService::missing);
    }
    @Transactional(readOnly=true)
    public List<Action> history(UUID tenant,UUID positionId) {
        List<UUID> present=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_position WHERE tenant_id=? AND id=?
            """,UUID.class,tenant,positionId);
        if(present.isEmpty())throw missing();
        return jdbc.query(ACTION_SQL+"""
            AND (source_position_id=? OR destination_position_id=?)
            ORDER BY created_at DESC,id DESC LIMIT 200
            """,ReleasedLotLifecycleService::map,tenant,positionId,positionId);
    }
    @Transactional(readOnly=true)
    public List<ExpiryAlert> expiry(UUID tenant,UUID warehouse) {
        return jdbc.query("""
            SELECT p.id,b.lot_code,z.bin_code,p.product_sku_id,p.remaining_quantity,
              p.expiry_date,
              CASE WHEN p.expiry_date<CURRENT_DATE THEN 'EXPIRED'
                   ELSE 'NEAR_EXPIRY' END AS urgency
            FROM erp_local_lot_position p
            JOIN erp_receipt_quality_batch b
              ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
            JOIN erp_local_stock_bin z
              ON z.tenant_id=p.tenant_id AND z.id=p.bin_id
            WHERE p.tenant_id=? AND p.warehouse_id=? AND p.remaining_quantity>0
              AND p.expiry_date<=CURRENT_DATE+30
            ORDER BY p.expiry_date ASC,p.id ASC LIMIT 300
            """,(rs,i)->new ExpiryAlert(rs.getObject("id",UUID.class).toString(),
                rs.getString("lot_code"),rs.getString("bin_code"),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getInt("remaining_quantity"),
                rs.getObject("expiry_date",LocalDate.class).toString(),
                rs.getString("urgency")),tenant,warehouse);
    }
    @Transactional(readOnly=true)
    public List<Lineage> lineage(UUID tenant,UUID positionId) {
        // Follow the immutable origin_position_id chain to the original released
        // V46 receipt. The join never crosses tenant boundaries.
        List<Lineage> rows=jdbc.query("""
            WITH RECURSIVE ancestors AS (
              SELECT p.id,p.origin_position_id,p.receipt_id,p.warehouse_id,
                     p.bin_id,p.placed_quantity,p.remaining_quantity,
                     p.expiry_date,p.tenant_id,0 AS depth
              FROM erp_local_lot_position p WHERE p.tenant_id=? AND p.id=?
              UNION ALL
              SELECT parent.id,parent.origin_position_id,parent.receipt_id,
                     parent.warehouse_id,parent.bin_id,parent.placed_quantity,
                     parent.remaining_quantity,parent.expiry_date,
                     parent.tenant_id,child.depth+1
              FROM erp_local_lot_position parent
              JOIN ancestors child ON child.origin_position_id=parent.id
                AND child.tenant_id=parent.tenant_id
              WHERE child.depth<100
            )
            SELECT a.id,a.origin_position_id,a.receipt_id,a.warehouse_id,
              z.bin_code,b.lot_code,a.placed_quantity,a.remaining_quantity,
              a.expiry_date,a.depth
            FROM ancestors a
            JOIN erp_local_stock_bin z ON z.tenant_id=a.tenant_id AND z.id=a.bin_id
            JOIN erp_receipt_quality_batch b
              ON b.tenant_id=a.tenant_id AND b.receipt_id=a.receipt_id
            ORDER BY a.depth DESC
            """,(rs,i)->{
                UUID origin=rs.getObject("origin_position_id",UUID.class);
                LocalDate expiry=rs.getObject("expiry_date",LocalDate.class);
                return new Lineage(rs.getObject("id",UUID.class).toString(),
                    origin==null?null:origin.toString(),
                    rs.getObject("receipt_id",UUID.class).toString(),
                    rs.getObject("warehouse_id",UUID.class).toString(),
                    rs.getString("bin_code"),rs.getString("lot_code"),
                    rs.getInt("placed_quantity"),rs.getInt("remaining_quantity"),
                    expiry==null?null:expiry.toString(),rs.getInt("depth"));
            },tenant,positionId);
        if(rows.isEmpty())throw missing();
        return rows;
    }
    private void lockTenant(UUID tenant) {
        List<UUID> ids=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(ids.size()!=1)throw missing();
    }
    private Source source(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT id,receipt_id,warehouse_id,product_sku_id,bin_id,
              remaining_quantity,expiry_date
            FROM erp_local_lot_position WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new Source(rs.getObject("id",UUID.class),
                rs.getObject("receipt_id",UUID.class),
                rs.getObject("warehouse_id",UUID.class),
                rs.getObject("product_sku_id",UUID.class),
                rs.getObject("bin_id",UUID.class),rs.getInt("remaining_quantity"),
                rs.getObject("expiry_date",LocalDate.class)),
            tenant,id).stream().findFirst().orElseThrow(ReleasedLotLifecycleService::missing);
    }
    private void lockWarehouses(UUID tenant,UUID source,UUID destination) {
        List<UUID> ids;
        if(destination==null)ids=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE'
            ORDER BY id FOR UPDATE
            """,UUID.class,tenant,source);
        else ids=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id IN (?,?) AND status='ACTIVE'
            ORDER BY id FOR UPDATE
            """,UUID.class,tenant,source,destination);
        if(ids.size()!=(destination==null?1:2))throw missing();
    }
    private UUID binWarehouse(UUID tenant,UUID bin) {
        if(bin==null)throw bad("Target STORAGE bin required");
        return jdbc.query("""
            SELECT warehouse_id FROM erp_local_stock_bin
            WHERE tenant_id=? AND id=? AND purpose='STORAGE' AND status='ACTIVE'
            """,(rs,i)->rs.getObject("warehouse_id",UUID.class),tenant,bin)
            .stream().findFirst().orElseThrow(ReleasedLotLifecycleService::missing);
    }
    private long stockBalance(UUID tenant,UUID warehouse,UUID sku) {
        Long n=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_local_stock_movement
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return n==null?0:n;
    }
    private static void check(Request req) {
        if(req==null || req.requestKey()==null || req.actionType()==null
            || req.expectedBinId()==null || req.expectedRemaining()<0 || req.expectedRemaining()>1_000_000
            || !List.of("MOVE","TRANSFER","COUNT_OBSERVATION",
                "EXPIRED_QUARANTINE").contains(req.actionType()))
            throw bad("Invalid released lot action");
        switch(req.actionType()) {
            case "MOVE" -> {
                if(req.quantity()!=0 || req.destinationBinId()==null
                    || req.expectedOnHand()!=null || req.observedQuantity()!=null)
                    throw bad("Move only changes STORAGE bin");
            }
            case "TRANSFER" -> {
                if(req.quantity()<1 || req.quantity()>1_000_000
                    || req.destinationBinId()==null || req.expectedOnHand()==null
                    || req.expectedOnHand()<0 || req.observedQuantity()!=null)
                    throw bad("Transfer requires quantity, destination, and stock snapshot");
            }
            case "EXPIRED_QUARANTINE" -> {
                if(req.quantity()<1 || req.quantity()>1_000_000
                    || req.destinationBinId()!=null || req.observedQuantity()!=null
                    || req.expectedOnHand()==null || req.expectedOnHand()<0)
                    throw bad("Expiry quarantine requires quantity and stock snapshot");
            }
            case "COUNT_OBSERVATION" -> {
                if(req.quantity()!=0 || req.destinationBinId()!=null
                    || req.expectedOnHand()!=null || req.observedQuantity()==null
                    || req.observedQuantity()<0 || req.observedQuantity()>1_000_000)
                    throw bad("Count observation never mutates warehouse inventory");
            }
            default -> throw bad("Unsupported lot action");
        }
    }
    private void event(UUID tenant,UUID actor,UUID position,String type,int quantity) {
        jdbc.update("""
            INSERT INTO erp_local_lot_event
              (id,tenant_id,position_id,event_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,position,type,quantity,actor);
    }
    private void requireCurrent(Source src,Request req) {
        if(src.remaining()!=req.expectedRemaining()
            || !src.bin().equals(req.expectedBinId()))
            throw conflict("Position quantity or source bin changed; refresh before action");
    }
    private void checkReplay(Action old,UUID actor,UUID sourceId,Request req) {
        if(!old.actorUserId().equals(actor.toString())
            || !old.sourcePositionId().equals(sourceId.toString())
            || !old.sourceBinId().equals(req.expectedBinId().toString())
            || !old.actionType().equals(req.actionType())
            || !Objects.equals(old.destinationBinId(),
                 req.destinationBinId()==null?null:req.destinationBinId().toString())
            || old.quantity()!=req.quantity()
            || old.expectedRemaining()!=req.expectedRemaining()
            || !Objects.equals(old.expectedOnHand(),req.expectedOnHand())
            || !Objects.equals(old.observedQuantity(),req.observedQuantity())
            || !old.reason().equals(reason(req.reason())))
            throw conflict("Lot action key was already used with another payload");
    }
    @Transactional
    public Action act(UUID tenant,UUID actor,UUID sourceId,Request req) {
        if(sourceId==null)throw bad("Source position required");
        check(req);
        String explanation=reason(req.reason());
        lockTenant(tenant);
        List<UUID> replay=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_action WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!replay.isEmpty()) {
            Action prior=get(tenant,replay.getFirst());
            checkReplay(prior,actor,sourceId,req);
            return prior;
        }
        Source src=source(tenant,sourceId);
        requireCurrent(src,req);
        UUID destinationWarehouse=null;
        UUID destinationPosition=null;
        UUID operation=null;
        if("MOVE".equals(req.actionType()) || "TRANSFER".equals(req.actionType())) {
            UUID binWh=binWarehouse(tenant,req.destinationBinId());
            if("MOVE".equals(req.actionType()) && !binWh.equals(src.warehouse()))
                throw conflict("In-warehouse move cannot cross warehouses");
            if("TRANSFER".equals(req.actionType())) {
                if(binWh.equals(src.warehouse()))
                    throw conflict("Cross-warehouse transfer must use another warehouse");
                destinationWarehouse=binWh;
            }
        }
        lockWarehouses(tenant,src.warehouse(),
            "TRANSFER".equals(req.actionType())?destinationWarehouse:null);
        long before=0;
        if(req.expectedOnHand()!=null) {
            before=stockBalance(tenant,src.warehouse(),src.sku());
            if(before!=req.expectedOnHand())
                throw conflict("Warehouse stock changed; refresh and retry");
            if(before<req.quantity())
                throw conflict("Insufficient usable warehouse stock");
        }
        UUID oldBin=src.bin();
        int quantity=req.quantity();
        switch(req.actionType()) {
            case "MOVE" -> {
                if(src.remaining()<1)
                    throw conflict("Empty storage position has no physical goods to move");
                if(oldBin.equals(req.destinationBinId()))
                    throw conflict("New STORAGE location must differ from old");
                int n=jdbc.update("""
                    UPDATE erp_local_lot_position SET bin_id=?
                    WHERE tenant_id=? AND id=? AND bin_id=? AND remaining_quantity=?
                    """,req.destinationBinId(),tenant,sourceId,oldBin,req.expectedRemaining());
                if(n!=1)throw conflict("Position changed before move");
                if(src.remaining()>0)event(tenant,actor,sourceId,"MOVED",src.remaining());
            }
            case "COUNT_OBSERVATION" -> {
                // This is evidence only. No V40 count, position changes or stock movements.
            }
            case "TRANSFER" -> {
                if(quantity>src.remaining())
                    throw conflict("Transfer exceeds positioned available quantity");
                if(src.expiry()!=null && src.expiry().isBefore(LocalDate.now()))
                    throw conflict("Expired released lot must be quarantined, not transferred");
                int n=jdbc.update("""
                    UPDATE erp_local_lot_position
                    SET remaining_quantity=remaining_quantity-?
                    WHERE tenant_id=? AND id=? AND remaining_quantity=?
                    """,quantity,tenant,sourceId,src.remaining());
                if(n!=1)throw conflict("Source lot changed before transfer");
                var posted=stock.post(tenant,actor,
                    new WarehouseStockOperationService.Request(UUID.randomUUID(),
                        "TRANSFER",src.warehouse(),destinationWarehouse,src.sku(),
                        quantity,req.expectedOnHand(),explanation));
                operation=UUID.fromString(posted.id());
                destinationPosition=UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO erp_local_lot_position
                      (id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,
                       bin_id,placed_quantity,remaining_quantity,expiry_date,reason,
                       created_by,origin_position_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,destinationPosition,tenant,UUID.randomUUID(),src.receipt(),
                    destinationWarehouse,src.sku(),req.destinationBinId(),
                    quantity,quantity,src.expiry(),explanation,actor,sourceId);
                event(tenant,actor,sourceId,"TRANSFER_OUT",quantity);
                event(tenant,actor,destinationPosition,"TRANSFER_IN",quantity);
            }
            case "EXPIRED_QUARANTINE" -> {
                if(src.expiry()==null || !src.expiry().isBefore(LocalDate.now()))
                    throw conflict("Only a documented expired lot may be quarantined here");
                if(quantity>src.remaining())
                    throw conflict("Quarantine quantity exceeds position stock");
                int n=jdbc.update("""
                    UPDATE erp_local_lot_position
                    SET remaining_quantity=remaining_quantity-?
                    WHERE tenant_id=? AND id=? AND remaining_quantity=?
                    """,quantity,tenant,sourceId,src.remaining());
                if(n!=1)throw conflict("Lot changed before quarantine");
                // The signed negative movement below is source-bound to this action.
            }
            default -> throw bad("Unsupported action");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_action
              (id,tenant_id,request_key,action_type,source_position_id,
               source_warehouse_id,destination_warehouse_id,source_bin_id,
               destination_bin_id,destination_position_id,operation_id,quantity,
               expected_remaining,expected_on_hand,observed_quantity,reason,actor_user_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),req.actionType(),sourceId,src.warehouse(),
            destinationWarehouse,oldBin,req.destinationBinId(),destinationPosition,
            operation,quantity,req.expectedRemaining(),req.expectedOnHand(),
            req.observedQuantity(),explanation,actor);
        if("EXPIRED_QUARANTINE".equals(req.actionType())) {
            jdbc.update("""
                INSERT INTO erp_local_stock_movement
                  (id,tenant_id,warehouse_id,product_sku_id,lot_action_id,
                   movement_type,quantity,actor_user_id)
                VALUES (?,?,?,?,?,'LOT_QUARANTINE',?,?)
                """,UUID.randomUUID(),tenant,src.warehouse(),src.sku(),id,-quantity,actor);
            event(tenant,actor,sourceId,"QUARANTINED",quantity);
        }
        return get(tenant,id);
    }
}
