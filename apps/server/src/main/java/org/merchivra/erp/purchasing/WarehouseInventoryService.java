package org.merchivra.erp.purchasing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local warehouse ledger for assigned purchase receipts and explicit manual movements.
 * Neither this ledger nor the resulting on-hand count is Amazon FBA/FBM inventory.
 */
@Service
public class WarehouseInventoryService {
    private final JdbcTemplate jdbc;
    public WarehouseInventoryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Warehouse(String id,String code,String name,String status) {}
    public record Stock(String warehouseId,String productSkuId,String internalSku,
                        long recordedReceivedQuantity,long onHandQuantity) {}
    public record Movement(String id,String warehouseId,String productSkuId,
                           String internalSku,String purchaseReceiptId,String operationId,
                           int quantity,String movementType,String reason,String createdAt) {}
    public record PendingReceipt(String purchaseOrderId,String orderNumber,
                                 String purchaseOrderLineId,String productSkuId,
                                 String internalSku,String status,int orderedQuantity,
                                 long receivedQuantity,long remainingQuantity,
                                 String currency,String unitCost) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static String validCode(String raw) {
        if(raw==null || !raw.matches("[A-Z0-9][A-Z0-9_-]{1,59}"))
            throw bad("Warehouse code must be 2..60 uppercase safe characters");
        return raw;
    }
    private static String validName(String raw) {
        if(raw==null || raw.strip().length()<2 || raw.strip().length()>200 ||
           raw.chars().anyMatch(Character::isISOControl))
            throw bad("Warehouse name must be 2..200 printable characters");
        return raw.strip();
    }
    private static Warehouse warehouse(java.sql.ResultSet rs,int row)
        throws java.sql.SQLException {
        return new Warehouse(rs.getObject("id",UUID.class).toString(),
            rs.getString("warehouse_code"),rs.getString("display_name"),
            rs.getString("status"));
    }
    public List<Warehouse> warehouses(UUID tenant) {
        return jdbc.query("""
            SELECT id,warehouse_code,display_name,status
            FROM erp_local_warehouse WHERE tenant_id=?
            ORDER BY warehouse_code LIMIT 200
            """,WarehouseInventoryService::warehouse,tenant);
    }
    @Transactional
    public Warehouse create(UUID tenant,UUID actor,String code,String name) {
        code=validCode(code);
        name=validName(name);
        UUID id=UUID.randomUUID();
        int count=jdbc.update("""
            INSERT INTO erp_local_warehouse
              (id,tenant_id,warehouse_code,display_name,created_by)
            VALUES (?,?,?,?,?)
            ON CONFLICT (tenant_id,warehouse_code) DO NOTHING
            """,id,tenant,code,name,actor);
        if(count!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Warehouse code already exists");
        return jdbc.query("""
            SELECT id,warehouse_code,display_name,status
            FROM erp_local_warehouse WHERE tenant_id=? AND id=?
            """,WarehouseInventoryService::warehouse,tenant,id).getFirst();
    }

    /** Called while the purchasing service holds the PO row lock. */
    void requireActive(UUID tenant,UUID warehouseId) {
        if(warehouseId==null)throw bad("Select a local warehouse before receiving");
        List<UUID> found=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE'
            FOR SHARE
            """,UUID.class,tenant,warehouseId);
        if(found.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Local warehouse unavailable");
    }
    void recordReceipt(UUID tenant,UUID actor,UUID warehouseId,UUID productSkuId,
                       UUID purchaseReceiptId,int quantity) {
        jdbc.update("""
            INSERT INTO erp_local_stock_movement
              (id,tenant_id,warehouse_id,product_sku_id,purchase_receipt_id,
               movement_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,'PURCHASE_RECEIPT',?,?)
            """,UUID.randomUUID(),tenant,warehouseId,productSkuId,purchaseReceiptId,
            quantity,actor);
    }
    private void requireReadable(UUID tenant,UUID warehouseId) {
        if(warehouseId==null)throw bad("Warehouse ID is required");
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM erp_local_warehouse WHERE tenant_id=? AND id=?
            """,Integer.class,tenant,warehouseId);
        if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    public List<Stock> stock(UUID tenant,UUID warehouseId) {
        requireReadable(tenant,warehouseId);
        return jdbc.query("""
            SELECT m.warehouse_id,m.product_sku_id,s.internal_sku,
                   SUM(CASE WHEN m.movement_type='PURCHASE_RECEIPT'
                     THEN m.quantity ELSE 0 END)::bigint AS received_units,
                   SUM(m.quantity)::bigint AS on_hand_units
            FROM erp_local_stock_movement m
            JOIN product_sku s ON s.tenant_id=m.tenant_id AND s.id=m.product_sku_id
            WHERE m.tenant_id=? AND m.warehouse_id=?
            GROUP BY m.warehouse_id,m.product_sku_id,s.internal_sku
            ORDER BY s.internal_sku LIMIT 500
            """,(rs,i)->new Stock(
                rs.getObject("warehouse_id",UUID.class).toString(),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getString("internal_sku"),rs.getLong("received_units"),
                rs.getLong("on_hand_units")),
            tenant,warehouseId);
    }
    public List<Movement> ledger(UUID tenant,UUID warehouseId) {
        requireReadable(tenant,warehouseId);
        return jdbc.query("""
            SELECT m.id,m.warehouse_id,m.product_sku_id,s.internal_sku,
                   m.purchase_receipt_id,m.operation_id,m.quantity,m.movement_type,
                   o.reason,m.created_at
            FROM erp_local_stock_movement m
            JOIN product_sku s ON s.tenant_id=m.tenant_id AND s.id=m.product_sku_id
            LEFT JOIN erp_local_stock_operation o
              ON o.tenant_id=m.tenant_id AND o.id=m.operation_id
            WHERE m.tenant_id=? AND m.warehouse_id=?
            ORDER BY m.created_at DESC,m.id DESC LIMIT 100
            """,(rs,i)->{
                UUID receipt=rs.getObject("purchase_receipt_id",UUID.class);
                UUID operation=rs.getObject("operation_id",UUID.class);
                return new Movement(
                    rs.getObject("id",UUID.class).toString(),
                    rs.getObject("warehouse_id",UUID.class).toString(),
                    rs.getObject("product_sku_id",UUID.class).toString(),
                    rs.getString("internal_sku"),
                    receipt==null?null:receipt.toString(),
                    operation==null?null:operation.toString(),
                    rs.getInt("quantity"),rs.getString("movement_type"),rs.getString("reason"),
                    rs.getObject("created_at",OffsetDateTime.class).toInstant().toString());
            },
            tenant,warehouseId);
    }

    /**
     * Approved purchase lines with unreceived quantity, not verified in-transit
     * shipments. No vendor ETA or Amazon inbound status is inferred.
     */
    public List<PendingReceipt> pendingReceipts(UUID tenant) {
        return jdbc.query("""
            SELECT o.id AS order_id,o.order_number,o.status,o.currency_code,
                l.id AS line_id,l.product_sku_id,s.internal_sku,
                l.ordered_quantity,l.unit_cost,
                COALESCE(SUM(r.quantity),0)::bigint AS received_units
            FROM erp_purchase_order o
            JOIN erp_purchase_order_line l
              ON l.tenant_id=o.tenant_id AND l.purchase_order_id=o.id
            JOIN product_sku s
              ON s.tenant_id=l.tenant_id AND s.id=l.product_sku_id
            LEFT JOIN erp_purchase_receipt r
              ON r.tenant_id=l.tenant_id AND r.purchase_order_id=o.id
              AND r.purchase_order_line_id=l.id
            WHERE o.tenant_id=? AND o.status IN ('APPROVED','RECEIVING')
            GROUP BY o.id,o.order_number,o.status,o.currency_code,
                l.id,l.product_sku_id,s.internal_sku,l.ordered_quantity,l.unit_cost
            HAVING l.ordered_quantity>COALESCE(SUM(r.quantity),0)
            ORDER BY o.order_number,l.id LIMIT 500
            """,(rs,i)->{
                int ordered=rs.getInt("ordered_quantity");
                long received=rs.getLong("received_units");
                return new PendingReceipt(
                    rs.getObject("order_id",UUID.class).toString(),
                    rs.getString("order_number"),
                    rs.getObject("line_id",UUID.class).toString(),
                    rs.getObject("product_sku_id",UUID.class).toString(),
                    rs.getString("internal_sku"),rs.getString("status"),ordered,
                    received,ordered-received,rs.getString("currency_code").trim(),
                    rs.getBigDecimal("unit_cost").toPlainString());
            },tenant);
    }
}
