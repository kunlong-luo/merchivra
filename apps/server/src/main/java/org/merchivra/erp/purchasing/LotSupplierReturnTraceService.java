package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V52: tie V49 expired released stock to the original supplier via the
 * original V20 receipt and PO. Not the V44 QC-rejected return subsystem.
 * Reported shipment/receipt only: no external supplier confirmation or stock.
 */
@Service
public class LotSupplierReturnTraceService {
    private final JdbcTemplate jdbc;
    public LotSupplierReturnTraceService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Dispatch(UUID requestKey,UUID dispositionId,
                           String carrierReference,String reason) {}
    public record Receipt(int receivedQuantity,String receiptReference,String reason) {}
    public record Trace(String id,String dispositionId,String sourceReceiptId,
                        String supplierId,int quantity,String carrierReference,
                        String reason,String status,String dispatchedBy,
                        Integer receivedQuantity,String receiptReference,
                        String receivedReportedBy) {}
    private record Source(UUID disposition,UUID receipt,UUID supplier,
                          int quantity,String status,String method) {}
    private static ResponseStatusException bad(String message){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message){
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Supplier-return trace unavailable");
    }
    private static String text(String raw,int min,int max){
        if(raw==null || raw.strip().length()<min || raw.strip().length()>max
            || raw.chars().anyMatch(Character::isISOControl))
            throw bad("Reference or reason length/characters invalid");
        return raw.strip();
    }
    private static String nullableId(ResultSet rs,String col) throws SQLException {
        UUID id=rs.getObject(col,UUID.class);
        return id==null?null:id.toString();
    }
    private static Trace map(ResultSet rs,int i) throws SQLException{
        int received=rs.getInt("received_quantity");
        boolean nullQty=rs.wasNull();
        return new Trace(nullableId(rs,"id"),nullableId(rs,"disposition_id"),
            nullableId(rs,"source_receipt_id"),nullableId(rs,"supplier_id"),
            rs.getInt("quantity"),rs.getString("carrier_reference"),
            rs.getString("reason"),rs.getString("status"),
            nullableId(rs,"dispatched_by"),nullQty?null:received,
            rs.getString("receipt_reference"),
            nullableId(rs,"received_reported_by"));
    }
    private static final String SELECT = """
        SELECT id,disposition_id,source_receipt_id,supplier_id,
          quantity,carrier_reference,reason,status,dispatched_by,
          received_quantity,receipt_reference,received_reported_by,
          dispatch_reported_at
        FROM erp_lot_supplier_return_trace WHERE tenant_id=?
        """;
    private Trace one(UUID tenant,UUID id){
        return jdbc.query(SELECT+" AND id=?",LotSupplierReturnTraceService::map,tenant,id)
            .stream().findFirst().orElseThrow(LotSupplierReturnTraceService::missing);
    }
    @Transactional(readOnly=true)
    public List<Trace> list(UUID tenant,UUID warehouse){
        return jdbc.query(SELECT+"""
            AND disposition_id IN (
              SELECT id FROM erp_local_lot_disposition
              WHERE tenant_id=? AND warehouse_id=?
            ) ORDER BY dispatch_reported_at DESC,id DESC LIMIT 200
            """,LotSupplierReturnTraceService::map,tenant,tenant,warehouse);
    }
    /** V120: complete warehouse return reports, never just the legacy first 200.
     * Cursor encodes tenant, warehouse and stable (dispatch time,id) keyset.
     * Human-reported dispatch/receipt is not proof from a carrier or supplier. */
    private record TraceRow(Trace trace,OffsetDateTime at) {}
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Trace> listPage(UUID tenant,UUID warehouse,
        int requested,String rawCursor) {
        int limit=LocalKeysetPage.limit(requested);
        String[] cursor=LocalKeysetPage.decode(rawCursor,5);
        OffsetDateTime at=null;
        UUID afterId=null;
        if(cursor!=null){
            if(!"return-v120".equals(cursor[0]) ||
               !tenant.toString().equals(cursor[1]) ||
               !warehouse.toString().equals(cursor[2]))
                throw LocalKeysetPage.bad("Return trace page scope mismatch");
            try {
                at=OffsetDateTime.parse(cursor[3]);
                afterId=UUID.fromString(cursor[4]);
            } catch(DateTimeParseException|IllegalArgumentException ex) {
                throw LocalKeysetPage.bad("Invalid supplier return cursor");
            }
        }
        String query=SELECT+"""
            AND disposition_id IN (
              SELECT id FROM erp_local_lot_disposition
              WHERE tenant_id=? AND warehouse_id=?
            )
            """+(cursor==null?"":" AND (dispatch_reported_at,id)<(?,?)")
            +" ORDER BY dispatch_reported_at DESC,id DESC LIMIT ?";
        List<TraceRow> records=cursor==null
            ?jdbc.query(query,(rs,i)->new TraceRow(map(rs,i),
                rs.getObject("dispatch_reported_at",OffsetDateTime.class)),
                tenant,tenant,warehouse,limit+1)
            :jdbc.query(query,(rs,i)->new TraceRow(map(rs,i),
                rs.getObject("dispatch_reported_at",OffsetDateTime.class)),
                tenant,tenant,warehouse,at,afterId,limit+1);
        var packed=LocalKeysetPage.pack(records,limit,row->LocalKeysetPage.encode(
            "return-v120",tenant.toString(),warehouse.toString(),
            row.at().toString(),row.trace().id()));
        return new LocalKeysetPage.Page<>(packed.items().stream()
            .map(TraceRow::trace).toList(),packed.nextCursor(),
            packed.hasMore(),packed.pageSize());
    }
    private void tenantLock(UUID tenant){
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private Source source(UUID tenant,UUID disposition){
        return jdbc.query("""
            SELECT d.id,p.receipt_id,po.supplier_id,d.quantity,d.status,d.method
            FROM erp_local_lot_disposition d
            JOIN erp_local_lot_action a
              ON a.tenant_id=d.tenant_id AND a.id=d.quarantine_action_id
              AND a.action_type='EXPIRED_QUARANTINE'
            JOIN erp_local_lot_position p
              ON p.tenant_id=d.tenant_id AND p.id=a.source_position_id
            JOIN erp_purchase_receipt r
              ON r.tenant_id=d.tenant_id AND r.id=p.receipt_id
            JOIN erp_purchase_order po
              ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
            WHERE d.tenant_id=? AND d.id=?
            """,(rs,i)->new Source(rs.getObject("id",UUID.class),
                rs.getObject("receipt_id",UUID.class),
                rs.getObject("supplier_id",UUID.class),
                rs.getInt("quantity"),rs.getString("status"),
                rs.getString("method")),tenant,disposition)
            .stream().findFirst().orElseThrow(LotSupplierReturnTraceService::missing);
    }
    @Transactional
    public Trace reportDispatch(UUID tenant,UUID actor,Dispatch req){
        if(req==null || req.requestKey()==null || req.dispositionId()==null)
            throw bad("Dispatch requires source and request key");
        String ref=text(req.carrierReference(),4,120),reason=text(req.reason(),5,300);
        tenantLock(tenant);
        List<UUID> sameKey=jdbc.queryForList("""
            SELECT id FROM erp_lot_supplier_return_trace
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!sameKey.isEmpty()){
            Trace saved=one(tenant,sameKey.getFirst());
            if(!saved.dispositionId().equals(req.dispositionId().toString())
                || !saved.carrierReference().equals(ref)
                || !saved.reason().equals(reason)
                || !saved.dispatchedBy().equals(actor.toString()))
                throw conflict("Dispatch key used with different evidence");
            return saved;
        }
        Source src=source(tenant,req.dispositionId());
        if(!"SUPPLIER_RETURN".equals(src.method())
            || !"EXECUTED_REPORTED".equals(src.status()))
            throw conflict("Only executed-reported supplier return may be traced");
        List<UUID> already=jdbc.queryForList("""
            SELECT id FROM erp_lot_supplier_return_trace
            WHERE tenant_id=? AND disposition_id=?
            """,UUID.class,tenant,req.dispositionId());
        if(!already.isEmpty())
            throw conflict("Supplier return disposition already has a dispatch trace");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_lot_supplier_return_trace
              (id,tenant_id,request_key,disposition_id,source_receipt_id,supplier_id,
               quantity,carrier_reference,reason,dispatched_by)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),src.disposition(),
            src.receipt(),src.supplier(),src.quantity(),ref,reason,actor);
        return one(tenant,id);
    }
    @Transactional
    public Trace reportReceipt(UUID tenant,UUID actor,UUID id,Receipt req){
        if(id==null || req==null || req.receivedQuantity()<0)
            throw bad("Supplier receipt quantity invalid");
        String ref=text(req.receiptReference(),4,120),reason=text(req.reason(),5,300);
        tenantLock(tenant);
        Trace old=one(tenant,id);
        if(actor.toString().equals(old.dispatchedBy()))
            throw conflict("Original dispatcher cannot self-attest supplier receipt");
        if(req.receivedQuantity()>old.quantity())
            throw bad("Received quantity exceeds dispatched quantity");
        if(!"DISPATCH_REPORTED".equals(old.status())){
            if(old.receivedQuantity()!=null && old.receivedQuantity()==req.receivedQuantity()
                && Objects.equals(old.receiptReference(),ref)
                && actor.toString().equals(old.receivedReportedBy())){
                List<String> notes=jdbc.query("""
                    SELECT receipt_note FROM erp_lot_supplier_return_trace
                    WHERE tenant_id=? AND id=?
                    """,(rs,i)->rs.getString(1),tenant,id);
                if(notes.size()==1 && notes.getFirst().equals(reason))return old;
            }
            throw conflict("Supplier receipt already reported with different facts");
        }
        jdbc.update("""
            UPDATE erp_lot_supplier_return_trace
            SET received_quantity=?,receipt_reference=?,receipt_note=?,
                received_reported_by=?,received_reported_at=now(),status=?
            WHERE tenant_id=? AND id=? AND status='DISPATCH_REPORTED'
            """,req.receivedQuantity(),ref,reason,actor,
            req.receivedQuantity()==old.quantity()?"SUPPLIER_RECEIPT_REPORTED":"RECEIPT_MISMATCH",
            tenant,id);
        // Manually reported supplier acknowledgement only, not an Amazon
        // shipment, not a verified external delivery and no second stock debit.
        return one(tenant,id);
    }
}
