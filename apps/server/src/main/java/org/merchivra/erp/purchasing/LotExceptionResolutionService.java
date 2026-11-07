package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * V51 exceptions: surplus may only bind existing signed and QC-released
 * unpositioned stock. A destination transfer shortage is debited once via
 * the existing V40 COUNT ledger, never a second TRANSFER_OUT.
 */
@Service
public class LotExceptionResolutionService {
    private final JdbcTemplate jdbc;
    private final WarehouseStockOperationService stock;
    public LotExceptionResolutionService(JdbcTemplate jdbc, WarehouseStockOperationService stock){
        this.jdbc=jdbc;this.stock=stock;
    }
    public record Proposal(UUID requestKey,String resolutionType,UUID sourceId,
                           UUID sourceReceiptId,String evidenceReference,String reason) {}
    public record Decision(String decision,String reason) {}
    public record Resolution(String id,String resolutionType,String countReviewId,
        String transferActionId,String sourceReceiptId,String positionId,
        String warehouseId,String productSkuId,String expectedBinId,
        int expectedPositionQuantity,long expectedOnHand,int adjustmentQuantity,
        String evidenceReference,String reason,String status,String proposedBy,
        String reviewedBy,String reviewReason,String operationId) {}
    private record TimedResolution(Resolution item,String createdAt) {}
    private record CountEvidence(UUID id,UUID position,UUID warehouse,UUID sku,UUID bin,
                                 int expected,int observed,String status) {}
    private record TransferEvidence(UUID id,UUID position,UUID warehouse,UUID sku,
                                    UUID bin,int expected,int observed,
                                    String status,UUID receiver) {}
    private record Position(UUID id,UUID receipt,UUID warehouse,UUID sku,
                            UUID bin,UUID origin,int placed,int remaining) {}
    private record ReceiptEvidence(String status,int accepted) {}
    private static ResponseStatusException bad(String message){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message){
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing(){
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Lot exception evidence unavailable");
    }
    private static String reason(String s){
        if(s==null || s.strip().length()<5 || s.strip().length()>300
            || s.chars().anyMatch(Character::isISOControl))
            throw bad("Explanation must be 5..300 printable characters");
        return s.strip();
    }
    private static String reference(String s){
        if(s==null || s.strip().length()<4 || s.strip().length()>120
            || s.chars().anyMatch(Character::isISOControl))
            throw bad("Document reference must be 4..120 printable characters");
        return s.strip();
    }
    private static String val(ResultSet rs,String field) throws SQLException {
        UUID id=rs.getObject(field,UUID.class);
        return id==null?null:id.toString();
    }
    private static Resolution map(ResultSet rs,int i) throws SQLException {
        return new Resolution(val(rs,"id"),rs.getString("resolution_type"),
            val(rs,"count_review_id"),val(rs,"transfer_action_id"),
            val(rs,"source_receipt_id"),val(rs,"position_id"),val(rs,"warehouse_id"),
            val(rs,"product_sku_id"),val(rs,"expected_bin_id"),
            rs.getInt("expected_position_quantity"),rs.getLong("expected_on_hand"),
            rs.getInt("adjustment_quantity"),rs.getString("evidence_reference"),
            rs.getString("reason"),rs.getString("status"),val(rs,"proposed_by"),
            val(rs,"reviewed_by"),rs.getString("review_reason"),val(rs,"operation_id"));
    }
    private static final String SQL = """
        SELECT id,resolution_type,count_review_id,transfer_action_id,
          source_receipt_id,position_id,warehouse_id,product_sku_id,
          expected_bin_id,expected_position_quantity,expected_on_hand,
          adjustment_quantity,evidence_reference,reason,status,proposed_by,
          reviewed_by,review_reason,operation_id
        FROM erp_local_lot_exception_resolution WHERE tenant_id=?
        """;
    @Transactional(readOnly=true)
    public List<Resolution> list(UUID tenant,UUID warehouse){
        return jdbc.query(SQL+"""
            AND warehouse_id=? ORDER BY created_at DESC,id DESC LIMIT 200
            """,LotExceptionResolutionService::map,tenant,warehouse);
    }
    /** V95: all warehouse approvals, not only 200 newest applications.
     * PENDING is the action queue; ALL includes applied/rejected history.
     */
    @Transactional(readOnly=true)
    public LocalKeysetPage.Page<Resolution> listPage(UUID tenant,UUID warehouse,
        String filter,int requested,String cursor) {
        if(!"PENDING".equals(filter) && !"ALL".equals(filter))throw bad(
            "Lot exception filter must be PENDING or ALL");
        int limit=LocalKeysetPage.limit(requested);
        String[] after=LocalKeysetPage.decode(cursor,5);
        if(after!=null && (!tenant.toString().equals(after[0])
            || !warehouse.toString().equals(after[1]) || !filter.equals(after[2])))
            throw bad("Lot exception cursor belongs to another tenant, warehouse or filter");
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(warehouse);
        String where="PENDING".equals(filter)?" AND status='PENDING_REVIEW'":"";
        if(after!=null) {
            try {
                where+=" AND (created_at,id) < (?,?)";
                args.add(OffsetDateTime.ofInstant(Instant.parse(after[3]),ZoneOffset.UTC));
                args.add(UUID.fromString(after[4]));
            } catch(Exception ex) {throw bad("Invalid lot exception cursor");}
        }
        args.add(limit+1);
        List<TimedResolution> rows=jdbc.query(
            SQL.replace("operation_id\\n", "operation_id,created_at\\n")+
            " AND warehouse_id=?"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",
            (rs,i)->new TimedResolution(map(rs,i),
                rs.getObject("created_at",OffsetDateTime.class).toInstant().toString()),
            args.toArray());
        var page=LocalKeysetPage.pack(rows,limit,r->LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),filter,
            r.createdAt(),r.item().id()));
        return new LocalKeysetPage.Page<>(
            page.items().stream().map(TimedResolution::item).toList(),
            page.nextCursor(),page.hasMore(),page.pageSize());
    }
    private Resolution one(UUID tenant,UUID id){
        return jdbc.query(SQL+" AND id=?",LotExceptionResolutionService::map,tenant,id)
            .stream().findFirst().orElseThrow(LotExceptionResolutionService::missing);
    }
    private void lockTenant(UUID tenant){
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private void lockWarehouse(UUID tenant,UUID warehouse){
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse WHERE tenant_id=?
              AND id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant,warehouse);
        if(rows.size()!=1)throw missing();
    }
    private long stockBalance(UUID tenant,UUID warehouse,UUID sku){
        Long n=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_local_stock_movement
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return n==null?0:n;
    }
    private long positioned(UUID tenant,UUID warehouse,UUID sku){
        Long n=jdbc.queryForObject("""
            SELECT COALESCE(SUM(remaining_quantity),0)::bigint
            FROM erp_local_lot_position
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return n==null?0:n;
    }
    private Position position(UUID tenant,UUID positionId){
        return jdbc.query("""
            SELECT id,receipt_id,warehouse_id,product_sku_id,bin_id,
              origin_position_id,placed_quantity,remaining_quantity
            FROM erp_local_lot_position
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new Position(rs.getObject("id",UUID.class),
                rs.getObject("receipt_id",UUID.class),
                rs.getObject("warehouse_id",UUID.class),
                rs.getObject("product_sku_id",UUID.class),
                rs.getObject("bin_id",UUID.class),
                rs.getObject("origin_position_id",UUID.class),
                rs.getInt("placed_quantity"),rs.getInt("remaining_quantity")),
            tenant,positionId).stream().findFirst()
            .orElseThrow(LotExceptionResolutionService::missing);
    }
    private CountEvidence count(UUID tenant,UUID id){
        return jdbc.query("""
            SELECT id,position_id,warehouse_id,product_sku_id,expected_bin_id,
              expected_position_quantity,observed_quantity,status
            FROM erp_local_lot_count_review
            WHERE tenant_id=? AND id=?
            """,(rs,i)->new CountEvidence(rs.getObject("id",UUID.class),
                rs.getObject("position_id",UUID.class),
                rs.getObject("warehouse_id",UUID.class),
                rs.getObject("product_sku_id",UUID.class),
                rs.getObject("expected_bin_id",UUID.class),
                rs.getInt("expected_position_quantity"),rs.getInt("observed_quantity"),
                rs.getString("status")),tenant,id).stream().findFirst()
            .orElseThrow(LotExceptionResolutionService::missing);
    }
    private TransferEvidence transfer(UUID tenant,UUID id){
        return jdbc.query("""
            SELECT a.id,a.destination_position_id,a.destination_warehouse_id,
              p.product_sku_id,a.destination_bin_id,
              a.quantity,k.received_quantity,k.status,k.acknowledged_by
            FROM erp_local_lot_action a
            JOIN erp_local_lot_transfer_ack k
              ON k.tenant_id=a.tenant_id AND k.transfer_action_id=a.id
            JOIN erp_local_lot_position p
              ON p.tenant_id=a.tenant_id AND p.id=a.destination_position_id
            WHERE a.tenant_id=? AND a.id=? AND a.action_type='TRANSFER'
            """,(rs,i)->new TransferEvidence(rs.getObject("id",UUID.class),
                rs.getObject("destination_position_id",UUID.class),
                rs.getObject("destination_warehouse_id",UUID.class),
                rs.getObject("product_sku_id",UUID.class),
                rs.getObject("destination_bin_id",UUID.class),
                rs.getInt("quantity"),rs.getInt("received_quantity"),
                rs.getString("status"),rs.getObject("acknowledged_by",UUID.class)),
            tenant,id).stream().findFirst().orElseThrow(LotExceptionResolutionService::missing);
    }
    private void match(Position p,UUID warehouse,UUID sku,UUID bin,int quantity){
        if(!p.warehouse().equals(warehouse) || !p.sku().equals(sku)
            || !p.bin().equals(bin) || p.remaining()!=quantity)
            throw conflict("Position, storage bin or physical quantity changed");
    }
    private ReceiptEvidence receipt(UUID tenant,UUID receiptId){
        return jdbc.query("""
            SELECT b.status,COALESCE(q.accepted_quantity,0)::integer accepted
            FROM erp_receipt_quality_batch b
            LEFT JOIN erp_purchase_receipt_qc q
              ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
            WHERE b.tenant_id=? AND b.receipt_id=?
            """,(rs,i)->new ReceiptEvidence(rs.getString("status"),
                rs.getInt("accepted")),tenant,receiptId).stream().findFirst()
                .orElseThrow(LotExceptionResolutionService::missing);
    }
    private long roots(UUID tenant,UUID receiptId){
        Long n=jdbc.queryForObject("""
            SELECT COALESCE(SUM(placed_quantity),0)::bigint
            FROM erp_local_lot_position
            WHERE tenant_id=? AND receipt_id=? AND origin_position_id IS NULL
            """,Long.class,tenant,receiptId);
        return n==null?0:n;
    }
    @Transactional
    public Resolution propose(UUID tenant,UUID actor,Proposal req){
        if(req==null || req.requestKey()==null || req.sourceId()==null
            || !List.of("RELEASED_SURPLUS_BIND","TRANSFER_SHORTAGE")
                .contains(req.resolutionType()))
            throw bad("Unsupported exception source or request key");
        String detail=reason(req.reason()),ref=reference(req.evidenceReference());
        if("RELEASED_SURPLUS_BIND".equals(req.resolutionType())
            && req.sourceReceiptId()==null)
            throw bad("Original approved receipt required for surplus");
        if("TRANSFER_SHORTAGE".equals(req.resolutionType())
            && req.sourceReceiptId()!=null)
            throw bad("Transfer shortage must not specify a new receipt");
        lockTenant(tenant);
        List<UUID> old=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_exception_resolution
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!old.isEmpty()){
            Resolution saved=one(tenant,old.getFirst());
            String source="RELEASED_SURPLUS_BIND".equals(saved.resolutionType())
                ?saved.countReviewId():saved.transferActionId();
            if(!saved.proposedBy().equals(actor.toString())
                || !saved.resolutionType().equals(req.resolutionType())
                || !source.equals(req.sourceId().toString())
                || !Objects.equals(saved.sourceReceiptId(),
                    req.sourceReceiptId()==null?null:req.sourceReceiptId().toString())
                || !saved.evidenceReference().equals(ref)
                || !saved.reason().equals(detail))
                throw conflict("Resolution key already used for a different submission");
            return saved;
        }
        UUID positionId,warehouse,sku,bin;
        int expected,adjustment;
        if("RELEASED_SURPLUS_BIND".equals(req.resolutionType())){
            CountEvidence c=count(tenant,req.sourceId());
            if(!"EVIDENCE_REQUIRED".equals(c.status())
                || c.observed()<=c.expected())
                throw conflict("Only independently reviewed surplus observations qualify");
            positionId=c.position();warehouse=c.warehouse();sku=c.sku();
            bin=c.bin();expected=c.expected();adjustment=c.observed()-c.expected();
        }else{
            TransferEvidence t=transfer(tenant,req.sourceId());
            if(!"MISMATCH".equals(t.status())
                || t.observed()>=t.expected())
                throw conflict("Only destination confirmed transfer shortages qualify");
            positionId=t.position();warehouse=t.warehouse();sku=t.sku();
            bin=t.bin();expected=t.expected();adjustment=t.expected()-t.observed();
        }
        List<UUID> used=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_exception_resolution
            WHERE tenant_id=? AND
              (count_review_id=? OR transfer_action_id=?)
            """,UUID.class,tenant,req.sourceId(),req.sourceId());
        if(!used.isEmpty())throw conflict("This exception already has a resolution");
        Position p=position(tenant,positionId);
        match(p,warehouse,sku,bin,expected);
        if("RELEASED_SURPLUS_BIND".equals(req.resolutionType())){
            if(p.origin()!=null || !p.receipt().equals(req.sourceReceiptId()))
                throw conflict("Surplus must be assigned to its original released receiving lot");
            ReceiptEvidence evidence=receipt(tenant,req.sourceReceiptId());
            if(!"RELEASED".equals(evidence.status()) || evidence.accepted()<1)
                throw conflict("No independently released goods support this surplus");
        }
        lockWarehouse(tenant,warehouse);
        long balance=stockBalance(tenant,warehouse,sku);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_exception_resolution
              (id,tenant_id,request_key,resolution_type,count_review_id,
               transfer_action_id,source_receipt_id,position_id,warehouse_id,
               product_sku_id,expected_bin_id,expected_position_quantity,
               expected_on_hand,adjustment_quantity,evidence_reference,reason,proposed_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),req.resolutionType(),
            "RELEASED_SURPLUS_BIND".equals(req.resolutionType())?req.sourceId():null,
            "TRANSFER_SHORTAGE".equals(req.resolutionType())?req.sourceId():null,
            req.sourceReceiptId(),positionId,warehouse,sku,bin,expected,
            balance,adjustment,ref,detail,actor);
        return one(tenant,id);
    }
    @Transactional
    public Resolution decide(UUID tenant,UUID actor,UUID id,Decision req){
        if(id==null || req==null || !List.of("APPROVE","REJECT").contains(req.decision()))
            throw bad("Decision must APPROVE or REJECT");
        String detail=reason(req.reason());
        lockTenant(tenant);
        Resolution x=one(tenant,id);
        if(!"PENDING_REVIEW".equals(x.status())){
            if(actor.toString().equals(x.reviewedBy())
                && Objects.equals(x.reviewReason(),detail)
                && ("REJECT".equals(req.decision())?"REJECTED".equals(x.status()):
                    "APPLIED".equals(x.status())))
                return x;
            throw conflict("Exception already resolved");
        }
        if(actor.toString().equals(x.proposedBy()))
            throw conflict("Proposer cannot approve or reject own resolution");
        if("TRANSFER_SHORTAGE".equals(x.resolutionType())){
            TransferEvidence t=transfer(tenant,UUID.fromString(x.transferActionId()));
            if(actor.equals(t.receiver()))
                throw conflict("Destination receiver cannot approve their own mismatch correction");
            if(!"MISMATCH".equals(t.status())
                || t.expected()-t.observed()!=x.adjustmentQuantity())
                throw conflict("Original destination mismatch evidence changed");
        }else{
            CountEvidence c=count(tenant,UUID.fromString(x.countReviewId()));
            if(!"EVIDENCE_REQUIRED".equals(c.status())
                || c.observed()-c.expected()!=x.adjustmentQuantity())
                throw conflict("Original surplus review evidence changed");
        }
        UUID wh=UUID.fromString(x.warehouseId());
        UUID sku=UUID.fromString(x.productSkuId());
        if("REJECT".equals(req.decision())){
            jdbc.update("""
                UPDATE erp_local_lot_exception_resolution
                SET status='REJECTED',reviewed_by=?,review_reason=?,reviewed_at=now()
                WHERE tenant_id=? AND id=? AND status='PENDING_REVIEW'
                """,actor,detail,tenant,id);
            return one(tenant,id);
        }
        lockWarehouse(tenant,wh);
        Position p=position(tenant,UUID.fromString(x.positionId()));
        match(p,wh,sku,UUID.fromString(x.expectedBinId()),
            x.expectedPositionQuantity());
        long balance=stockBalance(tenant,wh,sku);
        if(balance!=x.expectedOnHand())
            throw conflict("Warehouse signed stock changed since proposal");
        UUID operation=null;
        int delta=x.adjustmentQuantity();
        if("RELEASED_SURPLUS_BIND".equals(x.resolutionType())){
            if(p.origin()!=null || !p.receipt().toString().equals(x.sourceReceiptId()))
                throw conflict("Root released receipt source changed");
            ReceiptEvidence evidence=receipt(tenant,UUID.fromString(x.sourceReceiptId()));
            if(!"RELEASED".equals(evidence.status())
                || roots(tenant,p.receipt())+delta>evidence.accepted())
                throw conflict("No unassigned independently released QC units for surplus");
            if(positioned(tenant,wh,sku)+delta>balance)
                throw conflict("Surplus would exceed current signed usable local stock");
            if((long)p.placed()+delta>1_000_000 || (long)p.remaining()+delta>1_000_000)
                throw conflict("Lot position quantity exceeds allowed range");
            int n=jdbc.update("""
                UPDATE erp_local_lot_position
                SET placed_quantity=placed_quantity+?,
                    remaining_quantity=remaining_quantity+?
                WHERE tenant_id=? AND id=? AND bin_id=?
                  AND remaining_quantity=? AND placed_quantity=?
                """,delta,delta,tenant,p.id(),p.bin(),p.remaining(),p.placed());
            if(n!=1)throw conflict("Original root position changed");
            lotEvent(tenant,actor,p.id(),"SURPLUS_BOUND",delta);
            // No new signed movement: material is already in the warehouse
            // balance from the original approved QC_RELEASE, unpositioned.
        }else{
            TransferEvidence t=transfer(tenant,UUID.fromString(x.transferActionId()));
            if(!"MISMATCH".equals(t.status()) || t.expected()!=p.placed()
                || p.remaining()!=t.expected()
                || !p.id().equals(t.position()))
                throw conflict("Destination lot changed since transfer receipt");
            if(balance<delta)throw conflict("Destination ledger below shortage");
            long target=balance-delta;
            if(target>10_000_000)throw conflict("V40 count target outside range");
            int n=jdbc.update("""
                UPDATE erp_local_lot_position
                SET remaining_quantity=remaining_quantity-?
                WHERE tenant_id=? AND id=? AND bin_id=?
                  AND remaining_quantity=?
                """,delta,tenant,p.id(),p.bin(),p.remaining());
            if(n!=1)throw conflict("Destination lot changed before correction");
            String memo="V51 destination shortage "+id+"; "+detail;
            if(memo.length()>300)memo=memo.substring(0,300);
            var posted=stock.post(tenant,actor,new WarehouseStockOperationService.Request(
                UUID.randomUUID(),"COUNT",wh,null,sku,Math.toIntExact(target),
                balance,memo));
            operation=UUID.fromString(posted.id());
            lotEvent(tenant,actor,p.id(),"SHORTAGE_CORRECTED",delta);
        }
        jdbc.update("""
            UPDATE erp_local_lot_exception_resolution
            SET status='APPLIED',reviewed_by=?,review_reason=?,reviewed_at=now(),
                operation_id=?
            WHERE tenant_id=? AND id=? AND status='PENDING_REVIEW'
            """,actor,detail,operation,tenant,id);
        return one(tenant,id);
    }
    private void lotEvent(UUID tenant,UUID actor,UUID position,String event,int quantity){
        jdbc.update("""
            INSERT INTO erp_local_lot_event
              (id,tenant_id,position_id,event_type,quantity,actor_user_id)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,position,event,quantity,actor);
    }
}
