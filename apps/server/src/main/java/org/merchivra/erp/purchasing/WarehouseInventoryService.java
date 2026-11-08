package org.merchivra.erp.purchasing;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Locale;
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
                           String inspectionId,String lotActionId,int quantity,String movementType,String reason,
                           String createdAt) {}
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
    /**
     * V114: tenant-scoped warehouse directory. Codes are unique within a tenant;
     * UUID breaks ties. Search/status filters are bound to the opaque cursor,
     * and filtering happens before the 1..100 limit+1 keyset window.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Warehouse> warehousesPage(UUID tenant,
        int requested,String status,String prefix,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String filter=status==null||status.isBlank()?"ALL":status;
        if(!List.of("ALL","ACTIVE","ARCHIVED").contains(filter))
            throw bad("Unknown warehouse status filter");
        String term=prefix==null?"":prefix.strip().toUpperCase(Locale.ROOT);
        if(term.length()>60 || !term.matches("[A-Z0-9_-]*"))
            throw bad("Warehouse prefix must be 0..60 safe literal characters");
        String[] after=LocalKeysetPage.decode(cursor,6);
        String lastCode=null;
        UUID lastId=null;
        if(after!=null) {
            if(!"warehouse-v1".equals(after[0]) || !tenant.toString().equals(after[1])
                || !filter.equals(after[2]) || !("q:"+term).equals(after[3]))
                throw bad("Warehouse cursor does not match tenant or filters");
            if(!after[4].matches("[A-Z0-9][A-Z0-9_-]{1,59}"))
                throw bad("Invalid warehouse cursor code");
            lastCode=after[4];
            try { lastId=UUID.fromString(after[5]); }
            catch(IllegalArgumentException e) { throw bad("Invalid warehouse cursor"); }
        }
        StringBuilder sql=new StringBuilder("""
            SELECT id,warehouse_code,display_name,status
            FROM erp_local_warehouse WHERE tenant_id=?
            """);
        List<Object> args=new ArrayList<>();
        args.add(tenant);
        if(!"ALL".equals(filter)) {
            sql.append(" AND status=?");
            args.add(filter);
        }
        if(!term.isEmpty()) {
            // LEFT treats underscores literally; do not use unescaped LIKE.
            sql.append(" AND LEFT(warehouse_code,LENGTH(?))=?");
            args.add(term);
            args.add(term);
        }
        if(lastCode!=null) {
            sql.append(" AND (warehouse_code,id)>(?,?)");
            args.add(lastCode);
            args.add(lastId);
        }
        sql.append(" ORDER BY warehouse_code ASC,id ASC LIMIT ?");
        args.add(limit+1);
        List<Warehouse> fetched=jdbc.query(sql.toString(),
            WarehouseInventoryService::warehouse,args.toArray());
        return LocalKeysetPage.pack(fetched,limit,w->LocalKeysetPage.encode(
            "warehouse-v1",tenant.toString(),filter,"q:"+term,w.code(),w.id()));
    }

    /**
     * V115 read-only label resolver: one bounded tenant-scoped query for
     * a page of historical warehouse IDs, including archived warehouses.
     * Missing IDs are omitted, never resolved across tenants or guessed.
     */
    @Transactional(readOnly=true)
    public List<Warehouse> resolveWarehouses(UUID tenant,List<UUID> requested) {
        if(requested==null || requested.isEmpty() || requested.size()>100
            || requested.stream().anyMatch(java.util.Objects::isNull))
            throw bad("Select 1..100 warehouse IDs for labels");
        List<UUID> ids=new ArrayList<>(new java.util.LinkedHashSet<>(requested));
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        List<Object> args=new ArrayList<>();
        args.add(tenant);
        args.addAll(ids);
        return jdbc.query("""
            SELECT id,warehouse_code,display_name,status
            FROM erp_local_warehouse WHERE tenant_id=?
            AND id IN ("""+placeholders+"""
            ) ORDER BY warehouse_code ASC,id ASC
            """,WarehouseInventoryService::warehouse,args.toArray());
    }

    /** Exact lookup for deep-linked or previously saved selections. */
    @Transactional(readOnly=true)
    public Warehouse warehouseItem(UUID tenant,UUID warehouseId) {
        if(warehouseId==null)throw bad("Warehouse ID is required");
        List<Warehouse> found=jdbc.query("""
            SELECT id,warehouse_code,display_name,status FROM erp_local_warehouse
            WHERE tenant_id=? AND id=?
            """,WarehouseInventoryService::warehouse,tenant,warehouseId);
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Warehouse not found in active tenant");
        return found.getFirst();
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
        jdbc.update("""
            INSERT INTO erp_local_stock_bin
              (id,tenant_id,warehouse_id,bin_code,purpose,created_by)
            VALUES (?,?,?,'QC-HOLD','QC_HOLD',?)
            """,UUID.randomUUID(),tenant,id,actor);
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
        // V46: receipt is REAL physical arrival but not usable stock.
        // Historical V39 receipts retain existing ledger entries; only new
        // receipts get a pending-QC lot. QC_RELEASE later posts accepted units.
        String lotCode="LOT-"+purchaseReceiptId.toString().substring(0,12).toUpperCase();
        jdbc.update("""
            INSERT INTO erp_receipt_quality_batch
              (tenant_id,receipt_id,warehouse_id,product_sku_id,
               received_quantity,lot_code,received_by)
            VALUES (?,?,?,?,?,?,?)
            """,tenant,purchaseReceiptId,warehouseId,productSkuId,quantity,lotCode,actor);
        jdbc.update("""
            INSERT INTO erp_receipt_quality_batch_event
              (id,tenant_id,receipt_id,actor_user_id,event_type,new_lot_code,quantity,note)
            VALUES (?,?,?,?,'RECEIVED_TO_HOLD',?,?,'Physical receipt pending inspection')
            """,UUID.randomUUID(),tenant,purchaseReceiptId,actor,lotCode,quantity);
        List<UUID> hold=jdbc.queryForList("""
            SELECT id FROM erp_local_stock_bin
            WHERE tenant_id=? AND warehouse_id=? AND bin_code='QC-HOLD'
              AND purpose='QC_HOLD' AND status='ACTIVE'
            """,UUID.class,tenant,warehouseId);
        if(hold.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Warehouse is missing its receiving QC-hold location");
        UUID pieceId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_receipt_lot_piece
              (id,tenant_id,receipt_id,warehouse_id,product_sku_id,
               bin_id,piece_code,quantity,created_by)
            VALUES (?,?,?,?,? ,?,'ROOT',?,?)
            """,pieceId,tenant,purchaseReceiptId,warehouseId,productSkuId,
            hold.getFirst(),quantity,actor);
        jdbc.update("""
            INSERT INTO erp_receipt_lot_piece_event
              (id,tenant_id,receipt_id,piece_id,actor_user_id,
               event_type,quantity,new_bin_id,note)
            VALUES (?,?,?,?,?,'RECEIVED',?,?,'Received to virtual QC-hold location')
            """,UUID.randomUUID(),tenant,purchaseReceiptId,pieceId,actor,quantity,
            hold.getFirst());
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
            WITH sku_keys AS (
              SELECT m.tenant_id,m.warehouse_id,m.product_sku_id
              FROM erp_local_stock_movement m
              WHERE m.tenant_id=? AND m.warehouse_id=?
              UNION
              SELECT b.tenant_id,b.warehouse_id,b.product_sku_id
              FROM erp_receipt_quality_batch b
              WHERE b.tenant_id=? AND b.warehouse_id=?
            )
            SELECT k.warehouse_id,k.product_sku_id,s.internal_sku,
              COALESCE((
                SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                JOIN erp_purchase_order_line l
                  ON l.tenant_id=r.tenant_id AND l.id=r.purchase_order_line_id
                WHERE r.tenant_id=k.tenant_id AND r.warehouse_id=k.warehouse_id
                  AND l.product_sku_id=k.product_sku_id
              ),0)::bigint AS received_units,
              COALESCE((
                SELECT SUM(m.quantity) FROM erp_local_stock_movement m
                WHERE m.tenant_id=k.tenant_id AND m.warehouse_id=k.warehouse_id
                  AND m.product_sku_id=k.product_sku_id
              ),0)::bigint AS on_hand_units
            FROM sku_keys k
            JOIN product_sku s
              ON s.tenant_id=k.tenant_id AND s.id=k.product_sku_id
            ORDER BY s.internal_sku LIMIT 500
            """,(rs,i)->new Stock(
                rs.getObject("warehouse_id",UUID.class).toString(),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getString("internal_sku"),rs.getLong("received_units"),
                rs.getLong("on_hand_units")),
            tenant,warehouseId,tenant,warehouseId);
    }
    /** V113: full local warehouse stock keys, filtered before expensive aggregates. */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Stock> stockPage(UUID tenant,UUID warehouseId,
        int requested,String search,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        if(warehouseId==null)throw bad("Warehouse ID is required");
        String term=safeSearch(search);
        String[] after=LocalKeysetPage.decode(cursor,6);
        String lastSku=null;
        UUID lastId=null;
        if(after!=null) {
            if(!"stock-v1".equals(after[0]) || !tenant.toString().equals(after[1])
                || !warehouseId.toString().equals(after[2])
                || !("q:"+term).equals(after[3]))
                throw bad("Stock cursor does not match tenant, warehouse or search");
            lastSku=after[4];
            try { lastId=UUID.fromString(after[5]); }
            catch(IllegalArgumentException e) { throw bad("Invalid stock cursor"); }
        }
        requireReadable(tenant,warehouseId);
        StringBuilder sql=new StringBuilder("""
            WITH sku_keys AS (
                SELECT tenant_id,warehouse_id,product_sku_id
                FROM erp_local_stock_movement
                WHERE tenant_id=? AND warehouse_id=?
                UNION
                SELECT tenant_id,warehouse_id,product_sku_id
                FROM erp_receipt_quality_batch
                WHERE tenant_id=? AND warehouse_id=?
            ), page_keys AS MATERIALIZED (
                SELECT k.tenant_id,k.warehouse_id,k.product_sku_id,s.internal_sku
                FROM sku_keys k
                JOIN product_sku s
                  ON s.tenant_id=k.tenant_id AND s.id=k.product_sku_id
                WHERE 1=1
            """);
        List<Object> args=new ArrayList<>();
        args.add(tenant);args.add(warehouseId);args.add(tenant);args.add(warehouseId);
        if(!term.isEmpty()) {
            sql.append(" AND POSITION(? IN UPPER(s.internal_sku))>0");
            args.add(term);
        }
        if(lastSku!=null) {
            sql.append(" AND (s.internal_sku,k.product_sku_id)>(?,?)");
            args.add(lastSku);args.add(lastId);
        }
        sql.append("""
                ORDER BY s.internal_sku ASC,k.product_sku_id ASC LIMIT ?
            )
            SELECT k.warehouse_id,k.product_sku_id,k.internal_sku,
              COALESCE((
                SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                JOIN erp_purchase_order_line l
                  ON l.tenant_id=r.tenant_id AND l.id=r.purchase_order_line_id
                WHERE r.tenant_id=k.tenant_id AND r.warehouse_id=k.warehouse_id
                  AND l.product_sku_id=k.product_sku_id
              ),0)::bigint AS received_units,
              COALESCE((
                SELECT SUM(m.quantity) FROM erp_local_stock_movement m
                WHERE m.tenant_id=k.tenant_id AND m.warehouse_id=k.warehouse_id
                  AND m.product_sku_id=k.product_sku_id
              ),0)::bigint AS on_hand_units
            FROM page_keys k
            ORDER BY k.internal_sku ASC,k.product_sku_id ASC
            """);
        args.add(limit+1);
        List<Stock> fetched=jdbc.query(sql.toString(),
            WarehouseInventoryService::stockRow,args.toArray());
        return LocalKeysetPage.pack(fetched,limit,x->LocalKeysetPage.encode(
            "stock-v1",tenant.toString(),warehouseId.toString(),"q:"+term,
            x.internalSku(),x.productSkuId()));
    }

    /** Exact known-SKU observation for stock operations. A verified zero is
     * different from absence in a page or an inaccessible/unregistered SKU. */
    @Transactional(readOnly=true)
    public Stock stockItem(UUID tenant,UUID warehouseId,UUID productSkuId) {
        requireReadable(tenant,warehouseId);
        if(productSkuId==null)throw bad("SKU ID is required");
        List<Stock> rows=jdbc.query("""
            SELECT ?::uuid AS warehouse_id,s.id AS product_sku_id,s.internal_sku,
              COALESCE((
                SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                JOIN erp_purchase_order_line l
                  ON l.tenant_id=r.tenant_id AND l.id=r.purchase_order_line_id
                WHERE r.tenant_id=s.tenant_id AND r.warehouse_id=?
                  AND l.product_sku_id=s.id
              ),0)::bigint AS received_units,
              COALESCE((
                SELECT SUM(m.quantity) FROM erp_local_stock_movement m
                WHERE m.tenant_id=s.tenant_id AND m.warehouse_id=?
                  AND m.product_sku_id=s.id
              ),0)::bigint AS on_hand_units
            FROM product_sku s
            WHERE s.tenant_id=? AND s.id=? AND s.status='ACTIVE'
            """,WarehouseInventoryService::stockRow,
            warehouseId,warehouseId,warehouseId,tenant,productSkuId);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Active SKU is unavailable within this tenant");
        return rows.getFirst();
    }

    private static Stock stockRow(java.sql.ResultSet rs,int i)
        throws java.sql.SQLException {
        return new Stock(rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getString("internal_sku"),rs.getLong("received_units"),
            rs.getLong("on_hand_units"));
    }

    private static String safeSearch(String raw) {
        String term=raw==null?"":raw.strip().toUpperCase(Locale.ROOT);
        if(term.length()>80 || !term.matches("[A-Z0-9_-]*"))
            throw bad("Search must be 0..80 safe literal characters");
        return term;
    }

    public List<Movement> ledger(UUID tenant,UUID warehouseId) {
        requireReadable(tenant,warehouseId);
        return jdbc.query("""
            SELECT m.id,m.warehouse_id,m.product_sku_id,s.internal_sku,
                   m.purchase_receipt_id,m.operation_id,m.inspection_id,m.lot_action_id,
                   m.quantity,m.movement_type,COALESCE(o.reason,q.reason,a.reason) AS reason,m.created_at
            FROM erp_local_stock_movement m
            JOIN product_sku s ON s.tenant_id=m.tenant_id AND s.id=m.product_sku_id
            LEFT JOIN erp_local_stock_operation o
              ON o.tenant_id=m.tenant_id AND o.id=m.operation_id
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=m.tenant_id AND q.id=m.inspection_id
            LEFT JOIN erp_local_lot_action a
              ON a.tenant_id=m.tenant_id AND a.id=m.lot_action_id
            WHERE m.tenant_id=? AND m.warehouse_id=?
            ORDER BY m.created_at DESC,m.id DESC LIMIT 100
            """,WarehouseInventoryService::movement,tenant,warehouseId);
    }

    /**
     * V112: full tenant+warehouse-scoped local movement history. A live cursor
     * binds the warehouse, movement type and literal SKU substring. Rows
     * are ordered by timestamp and UUID to handle identical commit times.
     * No totals or immutable ledger snapshot are implied by a page.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Movement> ledgerPage(UUID tenant,UUID warehouseId,
        int requested,String movementType,String search,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        if(warehouseId==null)throw bad("Warehouse ID is required");
        String filter=movementType==null || movementType.isBlank()?"ALL":movementType;
        if(!List.of("ALL","PURCHASE_RECEIPT","OUTBOUND","TRANSFER_OUT",
            "TRANSFER_IN","COUNT_ADJUSTMENT","QC_QUARANTINE","QC_RELEASE",
            "LOT_QUARANTINE").contains(filter))
            throw bad("Unknown local movement type filter");
        String term=search==null?"":search.strip().toUpperCase(Locale.ROOT);
        if(term.length()>80 || term.chars().anyMatch(Character::isISOControl))
            throw bad("SKU search must contain at most 80 printable characters");
        String[] after=LocalKeysetPage.decode(cursor,7);
        OffsetDateTime lastAt=null;
        UUID lastId=null;
        if(after!=null) {
            if(!"ledger-v1".equals(after[0]) || !tenant.toString().equals(after[1])
                || !warehouseId.toString().equals(after[2])
                || !filter.equals(after[3]) || !("q:"+term).equals(after[4]))
                throw bad("Ledger cursor does not match tenant, warehouse or filters");
            try {
                Instant created=Instant.parse(after[5]);
                if(!created.toString().equals(after[5]))
                    throw bad("Invalid ledger pagination cursor");
                lastAt=OffsetDateTime.ofInstant(created,ZoneOffset.UTC);
                lastId=UUID.fromString(after[6]);
            } catch(java.time.format.DateTimeParseException | IllegalArgumentException e) {
                throw bad("Invalid ledger pagination cursor");
            }
        }
        // Reject cross-tenant, deleted or unknown warehouses even on empty pages.
        requireReadable(tenant,warehouseId);
        StringBuilder sql=new StringBuilder("""
            SELECT m.id,m.warehouse_id,m.product_sku_id,s.internal_sku,
                   m.purchase_receipt_id,m.operation_id,m.inspection_id,m.lot_action_id,
                   m.quantity,m.movement_type,COALESCE(o.reason,q.reason,a.reason) AS reason,m.created_at
            FROM erp_local_stock_movement m
            JOIN product_sku s ON s.tenant_id=m.tenant_id AND s.id=m.product_sku_id
            LEFT JOIN erp_local_stock_operation o
              ON o.tenant_id=m.tenant_id AND o.id=m.operation_id
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=m.tenant_id AND q.id=m.inspection_id
            LEFT JOIN erp_local_lot_action a
              ON a.tenant_id=m.tenant_id AND a.id=m.lot_action_id
            WHERE m.tenant_id=? AND m.warehouse_id=?
            """);
        List<Object> args=new ArrayList<>();
        args.add(tenant);
        args.add(warehouseId);
        if(!"ALL".equals(filter)) {
            sql.append(" AND m.movement_type=?");
            args.add(filter);
        }
        if(!term.isEmpty()) {
            // POSITION is literal, unlike LIKE's percentage/underscore wildcards.
            sql.append(" AND POSITION(LOWER(?) IN LOWER(s.internal_sku))>0");
            args.add(term);
        }
        if(lastAt!=null) {
            sql.append(" AND (m.created_at,m.id)<(?,?)");
            args.add(lastAt);
            args.add(lastId);
        }
        sql.append(" ORDER BY m.created_at DESC,m.id DESC LIMIT ?");
        args.add(limit+1);
        List<Movement> fetched=jdbc.query(sql.toString(),
            WarehouseInventoryService::movement,args.toArray());
        return LocalKeysetPage.pack(fetched,limit,m->LocalKeysetPage.encode(
            "ledger-v1",tenant.toString(),warehouseId.toString(),filter,
            "q:"+term,m.createdAt(),m.id()));
    }

    private static Movement movement(java.sql.ResultSet rs,int i)
        throws java.sql.SQLException {
        UUID receipt=rs.getObject("purchase_receipt_id",UUID.class);
        UUID operation=rs.getObject("operation_id",UUID.class);
        UUID inspection=rs.getObject("inspection_id",UUID.class);
        UUID lotAction=rs.getObject("lot_action_id",UUID.class);
        return new Movement(
            rs.getObject("id",UUID.class).toString(),
            rs.getObject("warehouse_id",UUID.class).toString(),
            rs.getObject("product_sku_id",UUID.class).toString(),
            rs.getString("internal_sku"),
            receipt==null?null:receipt.toString(),
            operation==null?null:operation.toString(),
            inspection==null?null:inspection.toString(),
            lotAction==null?null:lotAction.toString(),
            rs.getInt("quantity"),rs.getString("movement_type"),rs.getString("reason"),
            rs.getObject("created_at",OffsetDateTime.class).toInstant().toString());
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
    /** Full approved/receiving purchase line queue. HAVING is evaluated over
     * every persisted receipt before keyset limiting; no vendor ETA implied. */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<PendingReceipt> pendingReceiptsPage(UUID tenant,
        int requested,String status,String search,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String filter=status==null || status.isBlank()?"ALL":status;
        if(!List.of("ALL","APPROVED","RECEIVING").contains(filter))
            throw bad("Unknown pending receipt status");
        String term=safeSearch(search);
        String[] after=LocalKeysetPage.decode(cursor,6);
        String lastNumber=null;
        UUID lastLine=null;
        if(after!=null) {
            if(!"pending-v1".equals(after[0]) || !tenant.toString().equals(after[1])
                || !filter.equals(after[2]) || !("q:"+term).equals(after[3]))
                throw bad("Pending cursor does not match tenant or filters");
            lastNumber=after[4];
            try { lastLine=UUID.fromString(after[5]); }
            catch(IllegalArgumentException e){ throw bad("Invalid pending cursor"); }
        }
        StringBuilder sql=new StringBuilder("""
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
            """);
        List<Object> args=new ArrayList<>();
        args.add(tenant);
        if(!"ALL".equals(filter)) {
            sql.append(" AND o.status=?");
            args.add(filter);
        }
        if(!term.isEmpty()) {
            sql.append("""
                AND (POSITION(? IN UPPER(o.order_number))>0
                  OR POSITION(? IN UPPER(s.internal_sku))>0)
                """);
            args.add(term);args.add(term);
        }
        if(lastNumber!=null) {
            sql.append(" AND (o.order_number,l.id)>(?,?)");
            args.add(lastNumber);args.add(lastLine);
        }
        sql.append("""
            GROUP BY o.id,o.order_number,o.status,o.currency_code,
                l.id,l.product_sku_id,s.internal_sku,l.ordered_quantity,l.unit_cost
            HAVING l.ordered_quantity>COALESCE(SUM(r.quantity),0)
            ORDER BY o.order_number ASC,l.id ASC LIMIT ?
            """);
        args.add(limit+1);
        List<PendingReceipt> fetched=jdbc.query(sql.toString(),
            WarehouseInventoryService::pendingRow,args.toArray());
        return LocalKeysetPage.pack(fetched,limit,x->LocalKeysetPage.encode(
            "pending-v1",tenant.toString(),filter,"q:"+term,
            x.orderNumber(),x.purchaseOrderLineId()));
    }

    private static PendingReceipt pendingRow(java.sql.ResultSet rs,int i)
        throws java.sql.SQLException {
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
    }

}
