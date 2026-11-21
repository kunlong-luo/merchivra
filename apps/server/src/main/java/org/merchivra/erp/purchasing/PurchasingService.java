package org.merchivra.erp.purchasing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local purchase lifecycle. Quantities received are not FBA observations.
 * Approvals require a different ACTIVE Owner identity (controller checks RBAC).
 */
@Service
public class PurchasingService {
    private final JdbcTemplate jdbc;
    private final WarehouseInventoryService warehouses;
    public PurchasingService(JdbcTemplate jdbc,WarehouseInventoryService warehouses){
        this.jdbc=jdbc;
        this.warehouses=warehouses;
    }

    public record Supplier(String id,String code,String name,String status) {}
    public record Order(String id,String orderNumber,String supplierId,String status,
                        String currency,String createdBy,String approvedBy,String notes,
                        String updatedAt) {}
    public record Line(String id,String productSkuId,int orderedQuantity,
                       int receivedQuantity,String unitCost) {}
    public record Detail(Order order,List<Line> lines) {}
    public record RequestedLine(UUID productSkuId,int quantity,String unitCost) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static String code(String input) {
        if(input==null || !input.matches("[A-Z0-9][A-Z0-9_-]{1,59}"))
            throw bad("Supplier code must be 2..60 uppercase safe characters");
        return input;
    }
    private static String name(String input) {
        if(input==null || input.strip().length()<2 || input.strip().length()>200
           || input.chars().anyMatch(Character::isISOControl))
            throw bad("Supplier name must be 2..200 printable characters");
        return input.strip();
    }
    private static BigDecimal cost(String value) {
        if(value==null || !value.matches("[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw bad("Invalid purchase unit cost");
        return new BigDecimal(value);
    }
    private static String currency(String value) {
        if(value==null || !value.matches("[A-Z]{3}"))throw bad("Invalid ISO currency");
        return value;
    }
    private static Supplier supplier(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Supplier(rs.getObject("id",UUID.class).toString(),
            rs.getString("supplier_code"),rs.getString("display_name"),rs.getString("status"));
    }
    private static Order order(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        UUID approved=rs.getObject("approved_by",UUID.class);
        return new Order(rs.getObject("id",UUID.class).toString(),
            rs.getString("order_number"),rs.getObject("supplier_id",UUID.class).toString(),
            rs.getString("status"),rs.getString("currency_code").trim(),
            rs.getObject("created_by",UUID.class).toString(),
            approved==null?null:approved.toString(),rs.getString("notes"),
            rs.getObject("updated_at",OffsetDateTime.class).toInstant().toString());
    }
    public List<Supplier> suppliers(UUID tenant) {
        return jdbc.query("""
            SELECT id,supplier_code,display_name,status FROM erp_supplier
            WHERE tenant_id=? ORDER BY supplier_code LIMIT 200
            """,PurchasingService::supplier,tenant);
    }
    /**
     * V111: Tenant-bound full supplier directory. Supplier code is unique per
     * tenant; UUID remains a deterministic tie breaker. Pages are live views,
     * not snapshots. The legacy /suppliers endpoint remains compatible.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Supplier> suppliersPage(UUID tenant,int requested,
                                                        String status,String prefix,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String filter=(status==null || status.isBlank())?"ALL":status;
        if(!List.of("ALL","ACTIVE","ARCHIVED").contains(filter))
            throw bad("Unknown supplier status filter");
        String term=prefix==null?"":prefix.strip().toUpperCase(Locale.ROOT);
        if(term.length()>60 || !term.matches("[A-Z0-9_-]*"))
            throw bad("Supplier prefix must be 0..60 safe literal characters");
        String[] after=LocalKeysetPage.decode(cursor,6);
        String lastCode=null;
        UUID lastId=null;
        if(after!=null) {
            if(!"supplier-v1".equals(after[0]) || !tenant.toString().equals(after[1])
                || !filter.equals(after[2]) || !("q:"+term).equals(after[3]))
                throw bad("Supplier cursor does not match tenant or filters");
            if(!after[4].matches("[A-Z0-9][A-Z0-9_-]{1,59}"))
                throw bad("Invalid supplier pagination cursor");
            lastCode=after[4];
            try {
                lastId=UUID.fromString(after[5]);
            } catch(IllegalArgumentException e) {
                throw bad("Invalid supplier pagination cursor");
            }
        }
        StringBuilder sql=new StringBuilder("""
            SELECT id,supplier_code,display_name,status FROM erp_supplier
            WHERE tenant_id=?
            """);
        List<Object> params=new ArrayList<>();
        params.add(tenant);
        if(!"ALL".equals(filter)) {
            sql.append(" AND status=?");
            params.add(filter);
        }
        if(!term.isEmpty()) {
            // LEFT comparison treats underscores literally, unlike LIKE patterns.
            sql.append(" AND LEFT(supplier_code,LENGTH(?))=?");
            params.add(term);
            params.add(term);
        }
        if(lastCode!=null) {
            sql.append(" AND (supplier_code,id) > (?,?)");
            params.add(lastCode);
            params.add(lastId);
        }
        sql.append(" ORDER BY supplier_code ASC,id ASC LIMIT ?");
        params.add(limit+1);
        List<Supplier> fetched=jdbc.query(sql.toString(),PurchasingService::supplier,
            params.toArray());
        return LocalKeysetPage.pack(fetched,limit,s->LocalKeysetPage.encode(
            "supplier-v1",tenant.toString(),filter,"q:"+term,s.code(),s.id()));
    }

    @Transactional
    public Supplier createSupplier(UUID tenant,UUID actor,String supplierCode,String displayName) {
        supplierCode=code(supplierCode);displayName=name(displayName);
        UUID id=UUID.randomUUID();
        int changed=jdbc.update("""
            INSERT INTO erp_supplier(id,tenant_id,supplier_code,display_name)
            VALUES (?,?,?,?) ON CONFLICT (tenant_id,supplier_code) DO NOTHING
            """,id,tenant,supplierCode,displayName);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Supplier code already exists");
        return jdbc.query("""
            SELECT id,supplier_code,display_name,status FROM erp_supplier
            WHERE tenant_id=? AND id=?
            """,PurchasingService::supplier,tenant,id).getFirst();
    }

    public List<Order> orders(UUID tenant) {
        return jdbc.query("""
            SELECT id,order_number,supplier_id,status,currency_code,
              created_by,approved_by,notes,updated_at
            FROM erp_purchase_order WHERE tenant_id=?
            ORDER BY updated_at DESC,id DESC LIMIT 100
            """,PurchasingService::order,tenant);
    }
    /**
     * V110 server-scoped purchase history. The previous /orders endpoint remains
     * compatible but its first-100 result is not a complete tenant directory.
     * A cursor binds the tenant AND filters and uses updated_at+UUID to break ties.
     * Results are a live list, not a transactionally frozen audit snapshot.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Order> ordersPage(UUID tenant,int requested,
                                                   String status,String search,String cursor) {
        int limit=LocalKeysetPage.limit(requested);
        String filter=(status==null || status.isBlank())?"ALL":status;
        if(!List.of("ALL","DRAFT","SUBMITTED","APPROVED","RECEIVING",
                    "RECEIVED","CANCELLED").contains(filter))
            throw bad("Unknown purchase order status filter");
        String term=search==null?"":search.strip().toUpperCase(Locale.ROOT);
        if(term.length()>80 || !term.matches("[A-Z0-9_-]*"))
            throw bad("Purchase order search must be 0..80 safe literal characters");
        String[] after=LocalKeysetPage.decode(cursor,6);
        OffsetDateTime beforeTime=null;
        UUID beforeId=null;
        if(after!=null) {
            if(!"po-v1".equals(after[0]) || !tenant.toString().equals(after[1])
               || !filter.equals(after[2]) || !("q:"+term).equals(after[3]))
                throw bad("Purchase pagination cursor does not match tenant or filters");
            try {
                beforeTime=OffsetDateTime.ofInstant(Instant.parse(after[4]),ZoneOffset.UTC);
                beforeId=UUID.fromString(after[5]);
            } catch(DateTimeException | IllegalArgumentException e) {
                throw bad("Invalid purchase pagination cursor");
            }
        }
        StringBuilder sql=new StringBuilder("""
            SELECT id,order_number,supplier_id,status,currency_code,
              created_by,approved_by,notes,updated_at
            FROM erp_purchase_order
            WHERE tenant_id=?
            """);
        List<Object> params=new ArrayList<>();
        params.add(tenant);
        if(!"ALL".equals(filter)) {
            sql.append(" AND status=?");
            params.add(filter);
        }
        if(!term.isEmpty()) {
            // POSITION treats % and _ literally, unlike an unescaped LIKE pattern.
            sql.append(" AND POSITION(? IN UPPER(order_number))>0");
            params.add(term);
        }
        if(beforeTime!=null) {
            sql.append(" AND (updated_at,id) < (?,?)");
            params.add(beforeTime);
            params.add(beforeId);
        }
        sql.append(" ORDER BY updated_at DESC,id DESC LIMIT ?");
        params.add(limit+1);
        List<Order> fetched=jdbc.query(sql.toString(),PurchasingService::order,params.toArray());
        return LocalKeysetPage.pack(fetched,limit,o->LocalKeysetPage.encode(
            "po-v1",tenant.toString(),filter,"q:"+term,o.updatedAt(),o.id()));
    }

    private Order requireOrder(UUID tenant,UUID id,boolean lock) {
        List<Order> rows=jdbc.query("""
            SELECT id,order_number,supplier_id,status,currency_code,
              created_by,approved_by,notes,updated_at
            FROM erp_purchase_order WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            PurchasingService::order,tenant,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    public Detail detail(UUID tenant,UUID id) {
        Order value=requireOrder(tenant,id,false);
        List<Line> lines=jdbc.query("""
            SELECT l.id,l.product_sku_id,l.ordered_quantity,l.unit_cost,
              COALESCE(SUM(r.quantity),0)::integer AS received_quantity
            FROM erp_purchase_order_line l
            LEFT JOIN erp_purchase_receipt r
              ON r.tenant_id=l.tenant_id AND r.purchase_order_line_id=l.id
              AND r.purchase_order_id=l.purchase_order_id
            WHERE l.tenant_id=? AND l.purchase_order_id=?
            GROUP BY l.id,l.product_sku_id,l.ordered_quantity,l.unit_cost
            ORDER BY l.id
            """,(rs,n)->new Line(rs.getObject("id",UUID.class).toString(),
                rs.getObject("product_sku_id",UUID.class).toString(),
                rs.getInt("ordered_quantity"),rs.getInt("received_quantity"),
                rs.getBigDecimal("unit_cost").toPlainString()),tenant,id);
        return new Detail(value,lines);
    }
    @Transactional
    public Detail create(UUID tenant,UUID actor,UUID supplierId,String money,
                         String notes,List<RequestedLine> items) {
        currency(money);
        if(items==null || items.isEmpty() || items.size()>100)
            throw bad("Purchase order must contain 1..100 lines");
        if(notes!=null && (notes.length()>300 ||
            notes.chars().anyMatch(Character::isISOControl)))throw bad("Invalid notes");
        Set<UUID> seen=new HashSet<>();
        for(RequestedLine item:items){
            if(item==null || item.productSkuId()==null || item.quantity()<1
               || item.quantity()>1_000_000 || !seen.add(item.productSkuId()))
                throw bad("Invalid or duplicated purchase order line");
            cost(item.unitCost());
        }
        List<UUID> supplier=jdbc.query("""
            SELECT id FROM erp_supplier WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,supplierId);
        if(supplier.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Supplier unavailable");
        String placeholders=String.join(",",java.util.Collections.nCopies(seen.size(),"?"));
        java.util.List<Object> params=new java.util.ArrayList<>();
        params.add(tenant);
        params.addAll(seen);
        Integer skuCount=jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_sku WHERE tenant_id=? AND status='ACTIVE' AND id IN ("
              + placeholders + ")",Integer.class,params.toArray());
        if(skuCount==null || skuCount!=seen.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SKU unavailable");
        UUID id=UUID.randomUUID();
        String number="PO-"+id.toString().toUpperCase();
        jdbc.update("""
            INSERT INTO erp_purchase_order
              (id,tenant_id,supplier_id,order_number,currency_code,notes,created_by)
            VALUES (?,?,?,?,?,?,?)
            """,id,tenant,supplierId,number,money,notes,actor);
        for(RequestedLine item:items)jdbc.update("""
            INSERT INTO erp_purchase_order_line
              (id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,item.productSkuId(),
            item.quantity(),cost(item.unitCost()));
        audit(tenant,id,actor,"CREATED");
        return detail(tenant,id);
    }
    @Transactional
    public Detail transition(UUID tenant,UUID actor,UUID orderId,String action) {
        Order order=requireOrder(tenant,orderId,true);
        String next;
        switch(action) {
            case "submit" -> {
                if(!"DRAFT".equals(order.status()))throw invalidState();
                next="SUBMITTED";
            }
            case "approve" -> {
                if(!"SUBMITTED".equals(order.status()) ||
                    order.createdBy().equals(actor.toString()))throw invalidState();
                next="APPROVED";
            }
            case "cancel" -> {
                if(!List.of("DRAFT","SUBMITTED","APPROVED").contains(order.status()))
                    throw invalidState();
                Integer received=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM erp_purchase_receipt
                    WHERE tenant_id=? AND purchase_order_id=?
                    """,Integer.class,tenant,orderId);
                if(received!=null&&received>0)throw invalidState();
                next="CANCELLED";
            }
            default -> throw bad("Unsupported purchase transition");
        }
        int changed=jdbc.update("""
            UPDATE erp_purchase_order SET status=?,updated_at=now(),
              approved_by=CASE WHEN ?='APPROVED' THEN ? ELSE approved_by END,
              approved_at=CASE WHEN ?='APPROVED' THEN now() ELSE approved_at END
            WHERE tenant_id=? AND id=? AND status=?
            """,next,next,actor,next,tenant,orderId,order.status());
        if(changed!=1)throw invalidState();
        audit(tenant,orderId,actor,next);
        return detail(tenant,orderId);
    }
    @Transactional
    public Detail receive(UUID tenant,UUID actor,UUID orderId,UUID lineId,int quantity,
                          UUID warehouseId) {
        return receive(tenant,actor,orderId,lineId,quantity,warehouseId,null);
    }

    @Transactional
    public Detail receive(UUID tenant,UUID actor,UUID orderId,UUID lineId,int quantity,
                          UUID warehouseId,UUID asnId) {
        if(quantity<1 || quantity>1_000_000)throw bad("Receive quantity out of range");
        if(warehouseId==null)throw bad("Local warehouse is required");
        Order order=requireOrder(tenant,orderId,true);
        if(!List.of("APPROVED","RECEIVING").contains(order.status()))throw invalidState();
        Detail previous=detail(tenant,orderId);
        Line line=previous.lines().stream().filter(l->l.id().equals(lineId.toString()))
            .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(quantity>line.orderedQuantity()-line.receivedQuantity())throw invalidState();
        warehouses.requireActive(tenant,warehouseId);
        if(asnId!=null) {
            // PO lock is already held, serializing ASN creation, changes and
            // linked receipts. A supplier-reported shipment cannot over-receive.
            List<Integer> asnRemaining=jdbc.query("""
                SELECT a.quantity-COALESCE((SELECT SUM(r.quantity)
                  FROM erp_purchase_receipt r WHERE r.tenant_id=a.tenant_id
                  AND r.asn_id=a.id),0)::integer AS remaining
                FROM erp_purchase_asn a
                WHERE a.tenant_id=? AND a.purchase_order_id=?
                  AND a.purchase_order_line_id=? AND a.warehouse_id=?
                  AND a.id=? AND a.status='SHIPPED' FOR UPDATE
                """,(rs,i)->rs.getInt("remaining"),tenant,orderId,lineId,warehouseId,asnId);
            if(asnRemaining.size()!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Supplier ASN was not dispatched or does not match this PO/warehouse");
            if(asnRemaining.getFirst()<quantity)
                throw invalidState();
        }
        UUID receiptId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_purchase_receipt
              (id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,
               received_by,warehouse_id,asn_id)
            VALUES (?,?,?,?,?,?,?,?)
            """,receiptId,tenant,orderId,lineId,quantity,actor,warehouseId,asnId);
        if(asnId!=null) {
            jdbc.update("""
                INSERT INTO erp_purchase_asn_event
                  (id,tenant_id,asn_id,event_type,actor_user_id,quantity,note)
                VALUES (?,?,?,'RECEIPT_LINKED',?,?,'Actual local warehouse receipt')
                """,UUID.randomUUID(),tenant,asnId,actor,quantity);
        }
        // In the same transaction as the receipt and PO transition: no partial stock posting.
        warehouses.recordReceipt(tenant,actor,warehouseId,
            UUID.fromString(line.productSkuId()),receiptId,quantity);
        boolean complete=previous.lines().stream().allMatch(l->
            l.receivedQuantity()+(l.id().equals(lineId.toString())?quantity:0)==l.orderedQuantity());
        String next=complete?"RECEIVED":"RECEIVING";
        jdbc.update("""
            UPDATE erp_purchase_order SET status=?,updated_at=now()
            WHERE tenant_id=? AND id=?
            """,next,tenant,orderId);
        audit(tenant,orderId,actor,complete?"RECEIVED_FULL":"RECEIVED_PART");
        return detail(tenant,orderId);
    }
    private static ResponseStatusException invalidState() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Purchase order state or quantity changed");
    }
    private void audit(UUID tenant,UUID order,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO erp_purchase_order_audit
              (id,tenant_id,purchase_order_id,actor_user_id,event_type)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,order,actor,action);
    }
}
