package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * V50 evidence-first workflows. Warehouse count SHORTAGES are posted only
 * after independent review; unproven surplus is never fabricated as stock.
 * Transfer acknowledgements and quarantine disposal NEVER post stock again.
 */
@Service
public class LotReconciliationService {
    private final JdbcTemplate jdbc;
    private final WarehouseStockOperationService stock;
    public LotReconciliationService(JdbcTemplate jdbc,WarehouseStockOperationService stock) {
        this.jdbc=jdbc;this.stock=stock;
    }
    public record CountRequest(UUID requestKey,UUID observationActionId,String reason) {}
    public record Decision(String decision,String reason) {}
    public record CountCase(String id,String observationActionId,String positionId,
                            String warehouseId,String productSkuId,String expectedBinId,
                            int expectedPositionQuantity,int observedQuantity,
                            long expectedOnHand,String reason,String status,
                            String submittedBy,String reviewedBy,String operationId,
                            String reviewReason) {}
    public record AckRequest(UUID requestKey,int receivedQuantity,String reference,String reason) {}
    public record Transfer(String actionId,String destinationWarehouseId,
                           String sourcePositionId,String destinationPositionId,
                           int expectedQuantity,int receivedQuantity,String status,
                           String transferredBy,String acknowledgedBy,String reference) {}
    public record DispositionRequest(UUID requestKey,UUID quarantineActionId,
                                     String method,String reason) {}
    public record Completion(String reference,String reason) {}
    public record Disposition(String id,String quarantineActionId,String warehouseId,
                              int quantity,String method,String status,String reason,
                              String proposedBy,String reviewedBy,String completedBy,
                              String completionReference) {}
    private record Observation(UUID id,UUID position,UUID warehouse,UUID bin,
                               int expected,int observed,UUID observer) {}
    private record Snapshot(UUID warehouse,UUID sku,UUID bin,int remaining) {}
    private record Quarantine(UUID id,UUID warehouse,int quantity,UUID actor) {}

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);
    }
    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT,msg);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Lot reconciliation evidence unavailable");
    }
    private static String reason(String text) {
        if(text==null || text.strip().length()<5 || text.strip().length()>300
           || text.chars().anyMatch(Character::isISOControl))
            throw bad("Reason requires 5..300 printable characters");
        return text.strip();
    }
    private static String reference(String text) {
        if(text==null || text.strip().length()<4 || text.strip().length()>120
           || text.chars().anyMatch(Character::isISOControl))
            throw bad("Receiving/disposition reference requires 4..120 printable characters");
        return text.strip();
    }
    private static String uuid(ResultSet rs,String field) throws SQLException {
        UUID value=rs.getObject(field,UUID.class);
        return value==null?null:value.toString();
    }
    private void lockTenant(UUID tenant) {
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM tenant WHERE id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant);
        if(rows.size()!=1)throw missing();
    }
    private void lockWarehouse(UUID tenant,UUID warehouse) {
        List<UUID> rows=jdbc.queryForList("""
            SELECT id FROM erp_local_warehouse
            WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE
            """,UUID.class,tenant,warehouse);
        if(rows.size()!=1)throw missing();
    }
    private long balance(UUID tenant,UUID warehouse,UUID sku) {
        Long result=jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity),0)::bigint
            FROM erp_local_stock_movement
            WHERE tenant_id=? AND warehouse_id=? AND product_sku_id=?
            """,Long.class,tenant,warehouse,sku);
        return result==null?0:result;
    }
    private Observation observation(UUID tenant,UUID action) {
        return jdbc.query("""
            SELECT id,source_position_id,source_warehouse_id,source_bin_id,
                   expected_remaining,observed_quantity,actor_user_id
            FROM erp_local_lot_action
            WHERE tenant_id=? AND id=? AND action_type='COUNT_OBSERVATION'
            """,(rs,i)->new Observation(rs.getObject("id",UUID.class),
                rs.getObject("source_position_id",UUID.class),
                rs.getObject("source_warehouse_id",UUID.class),
                rs.getObject("source_bin_id",UUID.class),
                rs.getInt("expected_remaining"),rs.getInt("observed_quantity"),
                rs.getObject("actor_user_id",UUID.class)),tenant,action)
            .stream().findFirst().orElseThrow(LotReconciliationService::missing);
    }
    private Snapshot position(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT warehouse_id,product_sku_id,bin_id,remaining_quantity
            FROM erp_local_lot_position WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new Snapshot(rs.getObject("warehouse_id",UUID.class),
                rs.getObject("product_sku_id",UUID.class),
                rs.getObject("bin_id",UUID.class),rs.getInt("remaining_quantity")),
                tenant,id).stream().findFirst().orElseThrow(LotReconciliationService::missing);
    }
    private static CountCase mapCount(ResultSet rs,int i) throws SQLException {
        return new CountCase(uuid(rs,"id"),uuid(rs,"observation_action_id"),
            uuid(rs,"position_id"),uuid(rs,"warehouse_id"),uuid(rs,"product_sku_id"),
            uuid(rs,"expected_bin_id"),rs.getInt("expected_position_quantity"),
            rs.getInt("observed_quantity"),rs.getLong("expected_on_hand"),
            rs.getString("reason"),rs.getString("status"),uuid(rs,"submitted_by"),
            uuid(rs,"reviewed_by"),uuid(rs,"operation_id"),
            rs.getString("review_reason"));
    }
    private static final String COUNT_SQL = """
        SELECT id,observation_action_id,position_id,warehouse_id,product_sku_id,
          expected_bin_id,expected_position_quantity,observed_quantity,
          expected_on_hand,reason,status,submitted_by,reviewed_by,operation_id,review_reason
        FROM erp_local_lot_count_review WHERE tenant_id=?
        """;
    private CountCase count(UUID tenant,UUID id) {
        return jdbc.query(COUNT_SQL+" AND id=?",LotReconciliationService::mapCount,
            tenant,id).stream().findFirst().orElseThrow(LotReconciliationService::missing);
    }
    @Transactional(readOnly=true)
    public List<CountCase> counts(UUID tenant,UUID warehouse) {
        return jdbc.query(COUNT_SQL+"""
            AND warehouse_id=? ORDER BY created_at DESC,id DESC LIMIT 200
            """,LotReconciliationService::mapCount,tenant,warehouse);
    }
    @Transactional
    public CountCase submitCount(UUID tenant,UUID actor,CountRequest req) {
        if(req==null || req.requestKey()==null || req.observationActionId()==null)
            throw bad("Observation and idempotency key required");
        String detail=reason(req.reason());
        lockTenant(tenant);
        List<UUID> previous=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_count_review
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!previous.isEmpty()){
            CountCase prior=count(tenant,previous.getFirst());
            if(!prior.observationActionId().equals(req.observationActionId().toString())
               || !prior.reason().equals(detail)
               || !prior.submittedBy().equals(actor.toString()))
                throw conflict("Count case idempotency key used differently");
            return prior;
        }
        Observation obs=observation(tenant,req.observationActionId());
        Snapshot src=position(tenant,obs.position());
        if(!src.warehouse().equals(obs.warehouse()) ||
           !src.bin().equals(obs.bin()) || src.remaining()!=obs.expected())
            throw conflict("Count snapshot moved or changed since observation");
        lockWarehouse(tenant,obs.warehouse());
        UUID id=UUID.randomUUID();
        long available=balance(tenant,obs.warehouse(),src.sku());
        jdbc.update("""
            INSERT INTO erp_local_lot_count_review
              (id,tenant_id,request_key,observation_action_id,position_id,
               warehouse_id,product_sku_id,expected_bin_id,expected_position_quantity,
               observed_quantity,expected_on_hand,reason,submitted_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),obs.id(),obs.position(),obs.warehouse(),
            src.sku(),obs.bin(),obs.expected(),obs.observed(),available,detail,actor);
        return count(tenant,id);
    }
    @Transactional
    public CountCase decideCount(UUID tenant,UUID actor,UUID id,Decision req) {
        if(id==null || req==null || !List.of("APPROVE","REJECT").contains(req.decision()))
            throw bad("Decision must APPROVE or REJECT");
        String detail=reason(req.reason());
        lockTenant(tenant);
        CountCase c=count(tenant,id);
        if(!"PENDING_REVIEW".equals(c.status())) {
            if(actor.toString().equals(c.reviewedBy())
               && Objects.equals(c.reviewReason(),detail)
               && ("REJECT".equals(req.decision())?"REJECTED".equals(c.status()):
                   !"REJECTED".equals(c.status())))return c;
            throw conflict("Count case already reviewed");
        }
        Observation obs=observation(tenant,UUID.fromString(c.observationActionId()));
        if(actor.toString().equals(obs.observer().toString())
            || actor.toString().equals(c.submittedBy()))
            throw conflict("Observer/submitter cannot approve their own count");
        if("REJECT".equals(req.decision())) {
            jdbc.update("""
                UPDATE erp_local_lot_count_review
                SET status='REJECTED',reviewed_by=?,reviewed_at=now(),
                    review_reason=?
                WHERE tenant_id=? AND id=? AND status='PENDING_REVIEW'
                """,actor,detail,tenant,id);
            return count(tenant,id);
        }
        UUID warehouse=UUID.fromString(c.warehouseId());
        UUID sku=UUID.fromString(c.productSkuId());
        lockWarehouse(tenant,warehouse);
        Snapshot src=position(tenant,UUID.fromString(c.positionId()));
        if(!src.warehouse().equals(warehouse) || !src.sku().equals(sku)
            || !src.bin().toString().equals(c.expectedBinId())
            || src.remaining()!=c.expectedPositionQuantity())
            throw conflict("Count position or bin changed after submission");
        long before=balance(tenant,warehouse,sku);
        if(before!=c.expectedOnHand())throw conflict("Warehouse stock changed after count");
        String state;
        UUID operation=null;
        if(c.observedQuantity()>c.expectedPositionQuantity()) {
            // No inbound provenance exists for the surplus. Do not inflate
            // the released/approved lot or invent a positive warehouse receipt.
            state="EVIDENCE_REQUIRED";
        } else if(c.observedQuantity()==c.expectedPositionQuantity()) {
            state="CONFIRMED_NO_CHANGE";
        } else {
            int shortage=c.expectedPositionQuantity()-c.observedQuantity();
            if(before<shortage)throw conflict("Shortage exceeds signed available stock");
            long target=before-shortage;
            if(target>10_000_000)
                throw conflict("V40 physical count target exceeds supported range");
            int changed=jdbc.update("""
                UPDATE erp_local_lot_position SET remaining_quantity=?
                WHERE tenant_id=? AND id=? AND remaining_quantity=? AND bin_id=?
                """,c.observedQuantity(),tenant,UUID.fromString(c.positionId()),
                c.expectedPositionQuantity(),UUID.fromString(c.expectedBinId()));
            if(changed!=1)throw conflict("Position changed before approved adjustment");
            // Same transaction; generic V40 COUNT sees the reduced position
            // quantity and prevents any other positioned lot being consumed.
            var posted=stock.post(tenant,actor,new WarehouseStockOperationService.Request(
                UUID.randomUUID(),"COUNT",warehouse,null,sku,
                Math.toIntExact(target),before,
                ("V50 count "+id+"; "+detail).substring(0,
                    Math.min(300,("V50 count "+id+"; "+detail).length()))));
            operation=UUID.fromString(posted.id());
            state="APPLIED";
        }
        jdbc.update("""
            UPDATE erp_local_lot_count_review
            SET status=?,reviewed_by=?,reviewed_at=now(),review_reason=?,operation_id=?
            WHERE tenant_id=? AND id=? AND status='PENDING_REVIEW'
            """,state,actor,detail,operation,tenant,id);
        return count(tenant,id);
    }

    private static Transfer mapTransfer(ResultSet rs,int i) throws SQLException {
        int received=rs.getInt("received_quantity");
        boolean absent=rs.wasNull();
        return new Transfer(uuid(rs,"id"),uuid(rs,"destination_warehouse_id"),
            uuid(rs,"source_position_id"),uuid(rs,"destination_position_id"),
            rs.getInt("quantity"),absent?0:received,
            rs.getString("status"),uuid(rs,"actor_user_id"),
            uuid(rs,"acknowledged_by"),rs.getString("reference"));
    }
    private static final String TRANSFER_SQL = """
        SELECT a.id,a.destination_warehouse_id,a.source_position_id,
          a.destination_position_id,a.quantity,a.actor_user_id,
          k.received_quantity,k.acknowledged_by,k.reference,
          COALESCE(k.status,'POSTED_AWAITING_DESTINATION') AS status
        FROM erp_local_lot_action a
        LEFT JOIN erp_local_lot_transfer_ack k
          ON k.tenant_id=a.tenant_id AND k.transfer_action_id=a.id
        WHERE a.tenant_id=? AND a.action_type='TRANSFER'
        """;
    @Transactional(readOnly=true)
    public List<Transfer> transfers(UUID tenant,UUID warehouse) {
        return jdbc.query(TRANSFER_SQL+"""
            AND a.destination_warehouse_id=?
            ORDER BY a.created_at DESC,a.id DESC LIMIT 200
            """,LotReconciliationService::mapTransfer,tenant,warehouse);
    }
    private Transfer transfer(UUID tenant,UUID id) {
        return jdbc.query(TRANSFER_SQL+" AND a.id=?",
            LotReconciliationService::mapTransfer,tenant,id).stream().findFirst()
            .orElseThrow(LotReconciliationService::missing);
    }
    @Transactional
    public Transfer acknowledge(UUID tenant,UUID actor,UUID actionId,AckRequest req) {
        if(actionId==null || req==null || req.requestKey()==null
            || req.receivedQuantity()<0 || req.receivedQuantity()>1_000_000)
            throw bad("Invalid destination receiving quantity");
        String ref=reference(req.reference()), detail=reason(req.reason());
        lockTenant(tenant);
        Transfer transfer=transfer(tenant,actionId);
        if(actor.toString().equals(transfer.transferredBy()))
            throw conflict("Transfer operator cannot confirm own destination receipt");
        if(req.receivedQuantity()>transfer.expectedQuantity())
            throw bad("Received quantity cannot exceed posted transfer; report excess separately");
        List<UUID> keys=jdbc.queryForList("""
            SELECT transfer_action_id FROM erp_local_lot_transfer_ack
            WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!keys.isEmpty() && !keys.getFirst().equals(actionId))
            throw conflict("Receiving idempotency key belongs to another transfer");
        if(!"POSTED_AWAITING_DESTINATION".equals(transfer.status())) {
            List<String> unchanged=jdbc.query("""
                SELECT request_key,reason FROM erp_local_lot_transfer_ack
                WHERE tenant_id=? AND transfer_action_id=?
                """,(rs,i)->rs.getObject("request_key",UUID.class)+"|"+rs.getString("reason"),
                tenant,actionId);
            if(actor.toString().equals(transfer.acknowledgedBy())
                && transfer.receivedQuantity()==req.receivedQuantity()
                && Objects.equals(transfer.reference(),ref)
                && unchanged.size()==1
                && unchanged.getFirst().equals(req.requestKey()+"|"+detail))
                return transfer;
            throw conflict("Destination already confirmed with different evidence");
        }
        jdbc.update("""
            INSERT INTO erp_local_lot_transfer_ack
              (id,tenant_id,request_key,transfer_action_id,destination_warehouse_id,
               expected_quantity,received_quantity,status,reference,reason,acknowledged_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,req.requestKey(),actionId,
            UUID.fromString(transfer.destinationWarehouseId()),transfer.expectedQuantity(),
            req.receivedQuantity(),
            req.receivedQuantity()==transfer.expectedQuantity()?"CONFIRMED":"MISMATCH",
            ref,detail,actor);
        return transfer(tenant,actionId);
    }

    private Quarantine quarantine(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT id,source_warehouse_id,quantity,actor_user_id
            FROM erp_local_lot_action
            WHERE tenant_id=? AND id=? AND action_type='EXPIRED_QUARANTINE'
            """,(rs,i)->new Quarantine(rs.getObject("id",UUID.class),
                rs.getObject("source_warehouse_id",UUID.class),rs.getInt("quantity"),
                rs.getObject("actor_user_id",UUID.class)),tenant,id)
            .stream().findFirst().orElseThrow(LotReconciliationService::missing);
    }
    private static Disposition mapDisposition(ResultSet rs,int i) throws SQLException {
        return new Disposition(uuid(rs,"id"),uuid(rs,"quarantine_action_id"),
            uuid(rs,"warehouse_id"),rs.getInt("quantity"),rs.getString("method"),
            rs.getString("status"),rs.getString("reason"),uuid(rs,"proposed_by"),
            uuid(rs,"reviewed_by"),uuid(rs,"completed_by"),
            rs.getString("completion_reference"));
    }
    private static final String DISPOSITION_SQL = """
        SELECT id,quarantine_action_id,warehouse_id,quantity,method,status,reason,
               proposed_by,reviewed_by,completed_by,completion_reference
        FROM erp_local_lot_disposition WHERE tenant_id=?
        """;
    private Disposition disposition(UUID tenant,UUID id) {
        return jdbc.query(DISPOSITION_SQL+" AND id=?",
            LotReconciliationService::mapDisposition,tenant,id).stream().findFirst()
            .orElseThrow(LotReconciliationService::missing);
    }
    @Transactional(readOnly=true)
    public List<Disposition> dispositions(UUID tenant,UUID warehouse) {
        return jdbc.query(DISPOSITION_SQL+"""
            AND warehouse_id=? ORDER BY created_at DESC,id DESC LIMIT 200
            """,LotReconciliationService::mapDisposition,tenant,warehouse);
    }
    @Transactional(readOnly=true)
    public List<String> unplannedQuarantines(UUID tenant,UUID warehouse) {
        // All source movement amounts are already debited by V49; plans do not
        // affect signed stock a second time.
        return jdbc.query("""
            SELECT a.id::text FROM erp_local_lot_action a
            LEFT JOIN erp_local_lot_disposition d
              ON d.tenant_id=a.tenant_id AND d.quarantine_action_id=a.id
            WHERE a.tenant_id=? AND a.source_warehouse_id=?
              AND a.action_type='EXPIRED_QUARANTINE' AND d.id IS NULL
            ORDER BY a.created_at DESC,a.id DESC LIMIT 200
            """,(rs,i)->rs.getString(1),tenant,warehouse);
    }
    @Transactional
    public Disposition proposeDisposition(UUID tenant,UUID actor,DispositionRequest req) {
        if(req==null || req.requestKey()==null || req.quarantineActionId()==null
            || !List.of("DISPOSAL","SUPPLIER_RETURN").contains(req.method()))
            throw bad("Invalid quarantine disposition");
        String detail=reason(req.reason());
        lockTenant(tenant);
        List<UUID> existing=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_disposition WHERE tenant_id=? AND request_key=?
            """,UUID.class,tenant,req.requestKey());
        if(!existing.isEmpty()){
            Disposition prior=disposition(tenant,existing.getFirst());
            if(!prior.quarantineActionId().equals(req.quarantineActionId().toString())
                || !prior.method().equals(req.method()) || !prior.reason().equals(detail)
                || !prior.proposedBy().equals(actor.toString()))
                throw conflict("Disposition idempotency key reused with another source");
            return prior;
        }
        Quarantine source=quarantine(tenant,req.quarantineActionId());
        Long debited=jdbc.queryForObject("""
            SELECT COUNT(*)::bigint FROM erp_local_stock_movement
            WHERE tenant_id=? AND lot_action_id=?
              AND movement_type='LOT_QUARANTINE' AND quantity=?
            """,Long.class,tenant,source.id(),-source.quantity());
        if(debited==null || debited!=1L)
            throw conflict("Disposition requires one evidenced, previously debited quarantine");
        // One disposition plan per already debited quarantine event.
        List<UUID> claimed=jdbc.queryForList("""
            SELECT id FROM erp_local_lot_disposition
            WHERE tenant_id=? AND quarantine_action_id=?
            """,UUID.class,tenant,req.quarantineActionId());
        if(!claimed.isEmpty())throw conflict("Quarantine already has a disposition plan");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO erp_local_lot_disposition
              (id,tenant_id,request_key,quarantine_action_id,warehouse_id,quantity,
               method,reason,proposed_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,id,tenant,req.requestKey(),source.id(),source.warehouse(),
            source.quantity(),req.method(),detail,actor);
        return disposition(tenant,id);
    }
    @Transactional
    public Disposition decideDisposition(UUID tenant,UUID actor,UUID id,Decision req) {
        if(id==null || req==null || !List.of("APPROVE","REJECT").contains(req.decision()))
            throw bad("Disposition decision must APPROVE or REJECT");
        String detail=reason(req.reason());
        lockTenant(tenant);
        Disposition prior=disposition(tenant,id);
        if(!"PENDING_APPROVAL".equals(prior.status()))throw conflict("Plan already reviewed");
        Quarantine source=quarantine(tenant,UUID.fromString(prior.quarantineActionId()));
        if(actor.toString().equals(prior.proposedBy())
            || actor.equals(source.actor()))
            throw conflict("Quarantine proposer/operator cannot self-approve disposition");
        jdbc.update("""
            UPDATE erp_local_lot_disposition
            SET status=?,reviewed_by=?,reviewed_at=now(),review_reason=?
            WHERE tenant_id=? AND id=? AND status='PENDING_APPROVAL'
            ""","APPROVE".equals(req.decision())?"APPROVED":"REJECTED",
            actor,detail,tenant,id);
        return disposition(tenant,id);
    }
    @Transactional
    public Disposition reportCompletion(UUID tenant,UUID actor,UUID id,Completion req) {
        if(id==null || req==null)throw bad("Completion evidence required");
        String ref=reference(req.reference()), detail=reason(req.reason());
        lockTenant(tenant);
        Disposition prior=disposition(tenant,id);
        if("EXECUTED_REPORTED".equals(prior.status())) {
            List<String> saved=jdbc.query("""
                SELECT completion_reason FROM erp_local_lot_disposition
                WHERE tenant_id=? AND id=?
                """,(rs,i)->rs.getString(1),tenant,id);
            if(prior.completedBy().equals(actor.toString())
                && Objects.equals(prior.completionReference(),ref)
                && saved.size()==1 && saved.getFirst().equals(detail))return prior;
            throw conflict("Disposition completion already recorded");
        }
        if(!"APPROVED".equals(prior.status()))throw conflict("Plan not approved");
        if(actor.toString().equals(prior.reviewedBy()))
            throw conflict("Approver cannot self-report physical execution");
        jdbc.update("""
            UPDATE erp_local_lot_disposition
            SET status='EXECUTED_REPORTED',completed_by=?,completed_at=now(),
                completion_reference=?,completion_reason=?
            WHERE tenant_id=? AND id=? AND status='APPROVED'
            """,actor,ref,detail,tenant,id);
        // No inventory movement here: V49 quarantine has ALREADY debited it.
        return disposition(tenant,id);
    }
}
