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
 * Explicit LOCAL warehouse mutations only. A tenant-scoped idempotency key,
 * serialized warehouse row locks and signed append-only movements prevent
 * concurrent outbound writes from driving locally recorded stock negative.
 */
@Service
public class WarehouseStockOperationService {
    private final JdbcTemplate jdbc;
    public WarehouseStockOperationService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Request(UUID requestKey,String type,UUID warehouseId,
                          UUID destinationWarehouseId,UUID productSkuId,
                          int quantity,long expectedOnHand,String reason) {}
    public record Operation(String id,String type,String warehouseId,
                            String destinationWarehouseId,String productSkuId,
                            int requestedQuantity,long expectedOnHand,int appliedDelta,
                            long afterQuantity,String reason,String createdBy,
                            String createdAt) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    static int delta(String type,int requested,long available) {
        if(available<0)throw conflict("Local stock ledger has a negative balance");
        return switch(type) {
            case "OUTBOUND","TRANSFER" -> {
                if(requested<1 || requested>1_000_000)
                    throw bad("Outbound quantity must be 1..1000000");
                if(available<requested)throw conflict("Insufficient local warehouse stock");
                yield -requested;
            }
            case "COUNT" -> {
                if(requested<0 || requested>10_000_000)
                    throw bad("Physical count must be 0..10000000");
                long change=(long)requested-available;
                if(change==0)throw conflict("Physical count matches current stock");
                if(change<-1_000_000 || change>1_000_000)
                    throw bad("Count adjustment exceeds the 1000000-unit limit");
                yield Math.toIntExact(change);
            }
            default -> throw bad("Unsupported local stock operation");
        };
    }
    private static String validatedReason(String raw) {
        if(raw==null)throw bad("Reason is required");
        String reason=raw.strip();
        if(reason.length()<5 || reason.length()>300 ||
            reason.chars().anyMatch(Character::isISOControl))
            throw bad("Reason must contain 5..300 printable characters");
        return reason;
    }
    private static void validate(Request r) {
        if(r==null || r.requestKey()==null || r.warehouseId()==null
           || r.productSkuId()==null || r.expectedOnHand()<0)
            throw bad("Missing or invalid warehouse operation fields");
        if(!List.of("OUTBOUND","TRANSFER","COUNT").contains(r.type()))
            throw bad("Unsupported local stock operation");
        if("TRANSFER".equals(r.type())) {
            if(r.destinationWarehouseId()==null
               || r.destinationWarehouseId().equals(r.warehouseId()))
                throw bad("Transfer needs a different destination warehouse");
        } else if(r.destinationWarehouseId()!=null)
            throw bad("Destination is permitted only for a transfer");
        validatedReason(r.reason());
        // Pure input bounds, before obtaining locks or doing any database work.
        if("COUNT".equals(r.type())) {
            if(r.quantity()<0 || r.quantity()>10_000_000)
                throw bad("Physical count must be 0..10000000");
        } else if(r.quantity()<1 || r.quantity()>1_000_000)
            throw bad("Outbound quantity must be 1..1000000");
    }
    private static Operation map(ResultSet rs,int index) throws SQLException {
        UUID dst=rs.getObject("destination_warehouse_id",UUID.class);
        return new Operation(rs.getObject("id",UUID.class).toString(),
            rs.getString("operation_type"),
            rs.getObject("source_warehouse_id",UUID.class).toString(),
            dst==null?null:dst.toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getInt("requested_quantity"),rs.getLong("expected_on_hand"),
            rs.getInt("applied_delta"),rs.getLong("after_quantity"),
            rs.getString("reason"),rs.getObject("created_by",UUID.class).toString(),
            rs.getObject("created_at",OffsetDateTime.class).toInstant().toString());
    }
    private Operation get(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT id,operation_type,source_warehouse_id,destination_warehouse_id,
                   product_sku_id,requested_quantity,expected_on_hand,applied_delta,
                   after_quantity,reason,created_by,created_at
            FROM erp_local_stock_operation WHERE tenant_id=? AND id=?
            """,WarehouseStockOperationService::map,tenant,id).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private static boolean same(Operation saved,Request request,UUID actor,String reason) {
        return saved.type().equals(request.type())
            && saved.warehouseId().equals(request.warehouseId().toString())
            && java.util.Objects.equals(saved.destinationWarehouseId(),
                request.destinationWarehouseId()==null?null:request.destinationWarehouseId().toString())
            && saved.productSkuId().equals(request.productSkuId().toString())
            && saved.requestedQuantity()==request.quantity()
            && saved.expectedOnHand()==request.expectedOnHand()
            && saved.createdBy().equals(actor.toString())
            && saved.reason().equals(reason);
    }
    private static void lockWarehouses(JdbcTemplate jdbc,UUID tenant,Request req) {
        List<UUID> locked;
        if(req.destinationWarehouseId()==null) {
            locked=jdbc.queryForList("""
                SELECT id FROM erp_local_warehouse
                WHERE tenant_id=? AND id=? AND status='ACTIVE'
                ORDER BY id FOR UPDATE
                """,UUID.class,tenant,req.warehouseId());
        } else {
            locked=jdbc.queryForList("""
                SELECT id FROM erp_local_warehouse
                WHERE tenant_id=? AND id IN (?,?) AND status='ACTIVE'
                ORDER BY id FOR UPDATE
                """,UUID.class,tenant,req.warehouseId(),req.destinationWarehouseId());
        }
        if(locked.size()!=(req.destinationWarehouseId()==null?1:2))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Warehouse is not accessible or not active");
    }
    private long balance(UUID tenant,UUID warehouse,UUID sku) {
        Long value=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_local_stock_movement
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return value==null?0:value;
    }
    private void movement(UUID tenant,UUID actor,UUID warehouse,UUID sku,
                          UUID operation,String type,int amount) {
        jdbc.update("""
            INSERT INTO erp_local_stock_movement
              (id,tenant_id,warehouse_id,product_sku_id,operation_id,
               movement_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,warehouse,sku,operation,type,amount,actor);
    }

    @Transactional
    public Operation post(UUID tenant,UUID actor,Request req) {
        validate(req);
        String reason=validatedReason(req.reason());
        // Serialize retries across all warehouses for a tenant; never trust client tenantId.
        List<UUID> active=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(active.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<UUID> previous=jdbc.queryForList("""
            SELECT id FROM erp_local_stock_operation
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!previous.isEmpty()) {
            Operation old=get(tenant,previous.getFirst());
            if(!same(old,req,actor,reason))
                throw conflict("Idempotency key already used for a different operation");
            return old;
        }
        lockWarehouses(jdbc,tenant,req);
        List<UUID> skus=jdbc.queryForList("""
            SELECT id FROM product_sku WHERE tenant_id=? AND id=? AND status='ACTIVE'
            FOR SHARE
            """,UUID.class,tenant,req.productSkuId());
        if(skus.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Active internal SKU is unavailable");
        long before=balance(tenant,req.warehouseId(),req.productSkuId());
        if(before!=req.expectedOnHand())
            throw conflict("Local stock balance changed; refresh before confirmation");
        int change=delta(req.type(),req.quantity(),before);
        long after=before+change;
        if(after<0)throw conflict("Insufficient local warehouse stock");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_stock_operation
              (id,tenant_id,request_key,operation_type,source_warehouse_id,
               destination_warehouse_id,product_sku_id,requested_quantity,
               expected_on_hand,applied_delta,after_quantity,reason,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),req.type(),req.warehouseId(),
            req.destinationWarehouseId(),req.productSkuId(),req.quantity(),
            before,change,after,reason,actor);
        String sourceType=switch(req.type()) {
            case "OUTBOUND" -> "OUTBOUND";
            case "TRANSFER" -> "TRANSFER_OUT";
            case "COUNT" -> "COUNT_ADJUSTMENT";
            default -> throw new IllegalStateException("Validated operation type changed");
        };
        movement(tenant,actor,req.warehouseId(),req.productSkuId(),id,sourceType,change);
        if("TRANSFER".equals(req.type()))
            movement(tenant,actor,req.destinationWarehouseId(),req.productSkuId(),
                id,"TRANSFER_IN",req.quantity());
        return get(tenant,id);
    }

    public List<Operation> recent(UUID tenant,UUID warehouse) {
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_local_warehouse WHERE tenant_id=? AND id=?
            """,Integer.class,tenant,warehouse);
        if(count==null || count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return jdbc.query("""
            SELECT id,operation_type,source_warehouse_id,destination_warehouse_id,
                   product_sku_id,requested_quantity,expected_on_hand,applied_delta,
                   after_quantity,reason,created_by,created_at
            FROM erp_local_stock_operation
            WHERE tenant_id=? AND (source_warehouse_id=? OR destination_warehouse_id=?)
            ORDER BY created_at DESC,id DESC LIMIT 100
            """,WarehouseStockOperationService::map,tenant,warehouse,warehouse);
    }
}
