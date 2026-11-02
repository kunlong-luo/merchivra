package org.merchivra.erp.fba;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V77: tenant/marketplace-scoped, local human-workflow overlay on the V76
 * evidence classification. No Amazon APIs, receipts, PO, ASN or inventory writes.
 * A RESOLVED case is an operator disposition, never external verification.
 */
@Service
public class FbaPurchaseExceptionService {
    private final JdbcTemplate jdbc;
    private final FbaPurchaseReconciliationService reconciliation;
    public FbaPurchaseExceptionService(JdbcTemplate jdbc,
                                       FbaPurchaseReconciliationService reconciliation) {
        this.jdbc=jdbc;
        this.reconciliation=reconciliation;
    }
    public record Policy(boolean enabled,int leadHours,int defaultDueHours,long version) {}
    public record SavePolicy(boolean enabled,int leadHours,int defaultDueHours,
                             long expectedVersion,String note) {}
    public record Assignee(String userId,String displayName) {}
    public record WorkItem(String lineId,String shipmentId,String shipmentReference,
        String sellerSku,String findingCode,String severity,String fingerprint,
        String state,String assigneeId,String dueAt,String reminder,
        boolean stale,long version,String updatedAt) {}
    public record Board(boolean truncated,int shipmentScope,boolean amazonVerified,
        boolean fbaStockCredited,Policy policy,List<Assignee> assignees,
        List<WorkItem> items) {}
    public record Action(String action,long expectedVersion,String expectedFingerprint,
        UUID assigneeId,String dueAt,String note) {}
    public record Selection(UUID lineId,long expectedVersion,String expectedFingerprint) {}
    public record Bulk(UUID requestKey,List<Selection> lines,String note) {}
    public record BulkReceipt(String requestKey,int acknowledgedCount) {}
    public record History(String action,String oldState,String newState,
        String oldAssignee,String newAssignee,String oldDueAt,String newDueAt,
        String oldFingerprint,String newFingerprint,String note,String actorId,
        String occurredAt) {}
    public record PolicyHistory(long newVersion,Boolean oldEnabled,boolean newEnabled,
        Integer oldLeadHours,int newLeadHours,Integer oldDefaultDueHours,
        int newDefaultDueHours,String actorId,String note,String occurredAt) {}
    private record Case(UUID id,String state,UUID assignee,OffsetDateTime due,
                        String fingerprint,long version,OffsetDateTime updated) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Local exception line not available in this Marketplace");
    }
    private static String note(String note) {
        if(note==null || note.strip().length()<10 || note.strip().length()>300
           || note.chars().anyMatch(Character::isISOControl))
            throw bad("Action note must have 10..300 printable characters");
        return note.strip();
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    static String fingerprint(FbaPurchaseReconciliationService.Line line) {
        // All mutable local evidence affecting a review. Do not hash PO vs FBA
        // quantity deltas or make unrelated stages interchangeable.
        return hash(String.join("|",
            line.shipmentState(),Integer.toString(line.plannedQuantity()),
            Long.toString(line.locallyReportedQuantity()),
            Objects.toString(line.localVarianceDecision(),"-"),
            Objects.toString(line.linkId(),"-"),Objects.toString(line.linkState(),"-"),
            Objects.toString(line.purchaseOrderState(),"-"),
            Objects.toString(line.asnState(),"-"),
            line.findingCode(),line.severity()));
    }
    private void requireMarketplace(UUID tenant,UUID marketplace) {
        Integer active=jdbc.queryForObject("""
            SELECT count(*) FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,Integer.class,tenant,marketplace);
        if(active==null||active!=1)throw missing();
    }
    private void lockMarketplace(UUID tenant,UUID marketplace) {
        var rows=jdbc.queryForList("""
            SELECT m.id FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=? FOR UPDATE OF m
            """,UUID.class,tenant,marketplace);
        if(rows.size()!=1)throw missing();
    }
    private void scopedLine(UUID tenant,UUID marketplace,UUID line,boolean lock) {
        String sql="""
            SELECT s.id FROM fba_inbound_line l
            JOIN fba_inbound_shipment s ON s.tenant_id=l.tenant_id
              AND s.id=l.shipment_id
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            WHERE l.tenant_id=? AND s.seller_marketplace_id=? AND l.id=?
            """+(lock?" FOR UPDATE OF s":"");
        if(jdbc.queryForList(sql,UUID.class,tenant,marketplace,line).size()!=1)
            throw missing();
    }
    private static Case mapCase(ResultSet r,int i)throws SQLException {
        return new Case(r.getObject("id",UUID.class),r.getString("state"),
            r.getObject("assignee_id",UUID.class),
            r.getObject("due_at",OffsetDateTime.class),r.getString("source_fingerprint"),
            r.getLong("version"),r.getObject("updated_at",OffsetDateTime.class));
    }
    private Case caseFor(UUID tenant,UUID marketplace,UUID line,boolean lock) {
        var rows=jdbc.query("""
            SELECT id,state,assignee_id,due_at,source_fingerprint,version,updated_at
            FROM fba_purchase_exception_case WHERE tenant_id=?
              AND seller_marketplace_id=? AND inbound_line_id=?
            """+(lock?" FOR UPDATE":""),
            FbaPurchaseExceptionService::mapCase,tenant,marketplace,line);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Case> casesFor(UUID tenant,UUID marketplace,
                                     List<FbaPurchaseReconciliationService.Line> lines) {
        if(lines.isEmpty())return Map.of();
        String marks=String.join(",",java.util.Collections.nCopies(lines.size(),"?"));
        List<Object> args=new ArrayList<>();
        args.add(tenant);args.add(marketplace);
        for(var line:lines)args.add(UUID.fromString(line.inboundLineId()));
        var items=jdbc.query("""
            SELECT id,state,assignee_id,due_at,source_fingerprint,version,updated_at,
                   inbound_line_id
            FROM fba_purchase_exception_case WHERE tenant_id=?
              AND seller_marketplace_id=? AND inbound_line_id IN ("""+marks+")",
            (r,i)->Map.entry(r.getObject("inbound_line_id",UUID.class).toString(),
                             mapCase(r,i)),args.toArray());
        Map<String,Case> found=new HashMap<>();
        for(var entry:items)found.put(entry.getKey(),entry.getValue());
        return found;
    }
    private static String date(OffsetDateTime value) {
        return value==null?null:value.toInstant().toString();
    }
    private static String id(UUID value) {
        return value==null?null:value.toString();
    }
    private static String reminder(Policy policy,Case value,boolean stale) {
        if(value==null || value.due()==null || "RESOLVED".equals(value.state())&&!stale)
            return "NONE";
        if(stale)return "RECHECK";
        if(!policy.enabled())return "DISABLED";
        Instant now=Instant.now(),due=value.due().toInstant();
        if(!due.isAfter(now))return "OVERDUE";
        return !due.isAfter(now.plusSeconds(policy.leadHours()*3600L))
            ?"DUE_SOON":"NONE";
    }
    private static WorkItem view(FbaPurchaseReconciliationService.Line line,
                                 Case current,Policy policy) {
        String fp=fingerprint(line);
        boolean stale=current!=null && !fp.equals(current.fingerprint());
        return new WorkItem(line.inboundLineId(),line.shipmentId(),
            line.shipmentReference(),line.sellerSku(),line.findingCode(),
            line.severity(),fp,current==null?"OPEN":current.state(),
            current==null?null:id(current.assignee()),
            current==null?null:date(current.due()),
            reminder(policy,current,stale),stale,
            current==null?0:current.version(),
            current==null?null:date(current.updated()));
    }
    @Transactional(readOnly=true)
    public Policy policy(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        var rows=jdbc.query("""
            SELECT enabled,lead_hours,default_due_hours,version
            FROM fba_purchase_exception_policy
            WHERE tenant_id=? AND seller_marketplace_id=?
            """,(r,i)->new Policy(r.getBoolean(1),r.getInt(2),r.getInt(3),
                r.getLong(4)),tenant,marketplace);
        return rows.isEmpty()?new Policy(true,24,72,0):rows.getFirst();
    }
    @Transactional(readOnly=true)
    public Board board(UUID tenant,UUID marketplace) {
        var overview=reconciliation.overview(tenant,marketplace);
        var localCases=casesFor(tenant,marketplace,overview.lines());
        Policy settings=policy(tenant,marketplace);
        List<WorkItem> items=overview.lines().stream().map(line->
            view(line,localCases.get(line.inboundLineId()),settings)).toList();
        List<Assignee> assignees=jdbc.query("""
            SELECT DISTINCT u.id,u.identity_ref FROM membership m
            JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
            JOIN role_binding rb ON rb.tenant_id=m.tenant_id
                AND rb.user_id=m.user_id AND rb.role_code='OWNER'
                AND rb.seller_account_id IS NULL
            WHERE m.tenant_id=? AND m.status='ACTIVE'
            ORDER BY u.identity_ref,u.id LIMIT 100
            """,(r,i)->new Assignee(r.getObject(1,UUID.class).toString(),r.getString(2)),
            tenant);
        return new Board(overview.truncated(),overview.shipmentScope(),false,false,
            settings,assignees,items);
    }
    private FbaPurchaseReconciliationService.Line currentLine(
        UUID tenant,UUID marketplace,UUID line) {
        // Only actionable on the same bounded window the operator actually saw.
        return reconciliation.overview(tenant,marketplace).lines().stream()
            .filter(x->x.inboundLineId().equals(line.toString()))
            .findFirst().orElseThrow(()->conflict(
                "Line left the most recent 100 shipments/500-row review window"));
    }
    private void activeOwner(UUID tenant,UUID assignee) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM membership m
            JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
            JOIN role_binding rb ON rb.tenant_id=m.tenant_id
             AND rb.user_id=m.user_id AND rb.role_code='OWNER'
             AND rb.seller_account_id IS NULL
            WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE'
            """,Integer.class,tenant,assignee);
        if(count==null || count==0)throw bad("Assignee must be an active tenant Owner");
    }
    private static OffsetDateTime parseDue(String value) {
        if(value==null)throw bad("An ISO-8601 dueAt is required");
        try {
            OffsetDateTime due=OffsetDateTime.parse(value);
            if(!due.toInstant().isAfter(Instant.now())
               ||due.toInstant().isAfter(Instant.now().plusSeconds(366L*86400)))
                throw bad("Due time must be within the next 366 days");
            return due;
        } catch(java.time.format.DateTimeParseException e) {
            throw bad("Invalid ISO-8601 dueAt");
        }
    }
    private static String state(String name) {
        if(name==null || !List.of("ASSIGN","ACKNOWLEDGE","RESOLVE","REOPEN","SET_DUE").contains(name))
            throw bad("Unsupported local exception action");
        return name;
    }
    private WorkItem apply(UUID tenant,UUID marketplace,UUID actor,
                           FbaPurchaseReconciliationService.Line line,
                           Action action,Policy settings) {
        if(action==null || action.expectedVersion()<0
           ||action.expectedFingerprint()==null
           ||!action.expectedFingerprint().matches("[0-9a-f]{64}"))
            throw bad("Expected version and source fingerprint are required");
        String operation=state(action.action()),description=note(action.note());
        String live=fingerprint(line);
        if(!live.equals(action.expectedFingerprint()))
            throw conflict("Evidence changed since the last review; refresh first");
        UUID lineId=UUID.fromString(line.inboundLineId());
        Case old=caseFor(tenant,marketplace,lineId,true);
        long version=old==null?0:old.version();
        if(action.expectedVersion()!=version)
            throw conflict("Another Owner modified this exception; refresh first");
        boolean stale=old!=null && !live.equals(old.fingerprint());
        if(stale&&!List.of("ACKNOWLEDGE","REOPEN").contains(operation))
            throw conflict("Changed evidence must be acknowledged again first");
        if(old!=null && "RESOLVED".equals(old.state())
           && !("REOPEN".equals(operation)
             || ("ACKNOWLEDGE".equals(operation) && stale)))
            throw conflict("Reopen a resolved local case before editing");
        String next=old==null?"OPEN":old.state();
        UUID assignee=old==null?null:old.assignee();
        OffsetDateTime due=old==null?null:old.due();
        switch(operation) {
            case "ASSIGN" -> {
                if(action.assigneeId()==null)throw bad("Assignee is required");
                activeOwner(tenant,action.assigneeId());
                assignee=action.assigneeId();
            }
            case "SET_DUE" -> due=parseDue(action.dueAt());
            case "ACKNOWLEDGE" -> {
                next="ACKNOWLEDGED";
                if(assignee==null)assignee=actor;
            }
            case "RESOLVE" -> {
                if(old==null || !"ACKNOWLEDGED".equals(old.state())
                   ||assignee==null)
                    throw conflict("Assign and acknowledge before resolving");
                if("LOCAL_OVER_REPORT".equals(line.findingCode())
                   && !"ACCEPT_VARIANCE".equals(line.localVarianceDecision()))
                    throw conflict("High local over-report requires V65 variance review first");
                if("PENDING_IDENTITY_REVIEW".equals(line.findingCode()))
                    throw conflict("V75 second Owner must confirm/revoke identity first");
                next="RESOLVED";
            }
            case "REOPEN" -> {
                if(old==null)throw conflict("No existing exception to reopen");
                next="OPEN";
            }
            default -> throw bad("Unsupported action");
        }
        if(due==null)due=OffsetDateTime.now(ZoneOffset.UTC)
            .plusHours(settings.defaultDueHours());
        UUID caseId=old==null?UUID.randomUUID():old.id();
        if(old==null) {
            jdbc.update("""
                INSERT INTO fba_purchase_exception_case
                  (id,tenant_id,seller_marketplace_id,shipment_id,inbound_line_id,
                   state,assignee_id,due_at,source_fingerprint,last_action_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,caseId,tenant,marketplace,UUID.fromString(line.shipmentId()),
                lineId,next,assignee,due,live,actor);
        } else {
            jdbc.update("""
                UPDATE fba_purchase_exception_case
                SET state=?,assignee_id=?,due_at=?,source_fingerprint=?,
                    version=version+1,last_action_by=?,updated_at=now()
                WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
                """,next,assignee,due,live,actor,tenant,marketplace,caseId);
        }
        jdbc.update("""
            INSERT INTO fba_purchase_exception_audit
              (id,tenant_id,case_id,actor_user_id,action,old_state,new_state,
               old_assignee,new_assignee,old_due_at,new_due_at,
               old_fingerprint,new_fingerprint,note)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,caseId,actor,operation,
            old==null?null:old.state(),next,old==null?null:old.assignee(),
            assignee,old==null?null:old.due(),due,
            old==null?null:old.fingerprint(),live,description);
        return view(line,caseFor(tenant,marketplace,lineId,false),settings);
    }
    @Transactional
    public WorkItem act(UUID tenant,UUID marketplace,UUID actor,UUID line,Action action) {
        scopedLine(tenant,marketplace,line,true);
        var current=currentLine(tenant,marketplace,line);
        return apply(tenant,marketplace,actor,current,action,policy(tenant,marketplace));
    }
    @Transactional
    public BulkReceipt bulkAcknowledge(UUID tenant,UUID marketplace,UUID actor,Bulk body) {
        if(body==null || body.requestKey()==null || body.lines()==null
           ||body.lines().isEmpty() ||body.lines().size()>50)
            throw bad("Bulk acknowledgement requires 1..50 lines and a requestKey");
        String description=note(body.note());
        List<Selection> selected=new ArrayList<>(body.lines());
        if(selected.stream().anyMatch(x->x==null||x.lineId()==null
           ||x.expectedVersion()<0 ||x.expectedFingerprint()==null
           ||!x.expectedFingerprint().matches("[0-9a-f]{64}")))
            throw bad("Invalid bulk selection");
        selected.sort(Comparator.comparing(x->x.lineId().toString()));
        if(new HashSet<>(selected.stream().map(Selection::lineId).toList()).size()
           !=selected.size())throw bad("Duplicate line in bulk acknowledgement");
        String canonical=actor+"|"+description+"|"+selected;
        String payloadHash=hash(canonical);
        lockMarketplace(tenant,marketplace);
        var existing=jdbc.query("""
            SELECT payload_hash,actor_user_id,acknowledged_count
            FROM fba_purchase_exception_batch
            WHERE tenant_id=? AND seller_marketplace_id=? AND request_key=?
            FOR UPDATE
            """,(r,i)->new Object[]{r.getString(1),r.getObject(2,UUID.class),r.getInt(3)},
            tenant,marketplace,body.requestKey());
        if(!existing.isEmpty()) {
            Object[] saved=existing.getFirst();
            if(!saved[0].equals(payloadHash)||!saved[1].equals(actor))
                throw conflict("Bulk request key already belongs to another payload");
            return new BulkReceipt(body.requestKey().toString(),(Integer)saved[2]);
        }
        for(var choice:selected)scopedLine(tenant,marketplace,choice.lineId(),true);
        var lines=reconciliation.overview(tenant,marketplace).lines();
        Map<String,FbaPurchaseReconciliationService.Line> byId=new HashMap<>();
        for(var line:lines)byId.put(line.inboundLineId(),line);
        Policy settings=policy(tenant,marketplace);
        for(var choice:selected) {
            var line=byId.get(choice.lineId().toString());
            if(line==null)throw conflict("Bulk line outside visible bounded review window");
            apply(tenant,marketplace,actor,line,new Action("ACKNOWLEDGE",
                choice.expectedVersion(),choice.expectedFingerprint(),
                null,null,description),settings);
        }
        jdbc.update("""
            INSERT INTO fba_purchase_exception_batch
              (id,tenant_id,seller_marketplace_id,request_key,payload_hash,
               actor_user_id,acknowledged_count)
            VALUES (?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,marketplace,body.requestKey(),
            payloadHash,actor,selected.size());
        return new BulkReceipt(body.requestKey().toString(),selected.size());
    }
    @Transactional
    public Policy savePolicy(UUID tenant,UUID marketplace,UUID actor,SavePolicy input) {
        if(input==null||input.expectedVersion()<0||input.leadHours()<1
           ||input.leadHours()>168||input.defaultDueHours()<1
           ||input.defaultDueHours()>720)
            throw bad("Invalid reminder policy bounds");
        String description=note(input.note());
        lockMarketplace(tenant,marketplace);
        Policy old=policy(tenant,marketplace);
        if(old.version()!=input.expectedVersion())
            throw conflict("Reminder policy was changed by another Owner");
        long version=old.version()+1;
        if(old.version()==0) {
            jdbc.update("""
                INSERT INTO fba_purchase_exception_policy
                  (tenant_id,seller_marketplace_id,enabled,lead_hours,
                   default_due_hours,version,updated_by)
                VALUES (?,?,?,?,?,1,?)
                """,tenant,marketplace,input.enabled(),
                input.leadHours(),input.defaultDueHours(),actor);
        } else {
            jdbc.update("""
                UPDATE fba_purchase_exception_policy
                SET enabled=?,lead_hours=?,default_due_hours=?,version=?,
                    updated_by=?,updated_at=now()
                WHERE tenant_id=? AND seller_marketplace_id=?
                """,input.enabled(),input.leadHours(),input.defaultDueHours(),
                version,actor,tenant,marketplace);
        }
        jdbc.update("""
            INSERT INTO fba_purchase_exception_policy_audit
              (id,tenant_id,seller_marketplace_id,actor_user_id,
               old_version,new_version,old_enabled,new_enabled,
               old_lead_hours,new_lead_hours,old_default_due_hours,
               new_default_due_hours,note)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,marketplace,actor,
            old.version()==0?null:old.version(),version,
            old.version()==0?null:old.enabled(),input.enabled(),
            old.version()==0?null:old.leadHours(),input.leadHours(),
            old.version()==0?null:old.defaultDueHours(),input.defaultDueHours(),
            description);
        return new Policy(input.enabled(),input.leadHours(),input.defaultDueHours(),version);
    }
    @Transactional(readOnly=true)
    public List<History> history(UUID tenant,UUID marketplace,UUID line) {
        scopedLine(tenant,marketplace,line,false);
        return jdbc.query("""
            SELECT a.action,a.old_state,a.new_state,a.old_assignee,a.new_assignee,
              a.old_due_at,a.new_due_at,a.old_fingerprint,a.new_fingerprint,
              a.note,a.actor_user_id,a.occurred_at
            FROM fba_purchase_exception_audit a
            JOIN fba_purchase_exception_case c ON c.tenant_id=a.tenant_id
              AND c.id=a.case_id
            WHERE c.tenant_id=? AND c.seller_marketplace_id=? AND c.inbound_line_id=?
            ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100
            """,(r,i)->new History(r.getString(1),r.getString(2),r.getString(3),
                id(r.getObject(4,UUID.class)),id(r.getObject(5,UUID.class)),
                date(r.getObject(6,OffsetDateTime.class)),
                date(r.getObject(7,OffsetDateTime.class)),r.getString(8),r.getString(9),
                r.getString(10),id(r.getObject(11,UUID.class)),
                date(r.getObject(12,OffsetDateTime.class))),
            tenant,marketplace,line);
    }
    @Transactional(readOnly=true)
    public List<PolicyHistory> policyHistory(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            SELECT old_enabled,new_enabled,old_lead_hours,new_lead_hours,
              old_default_due_hours,new_default_due_hours,new_version,
              actor_user_id,note,occurred_at
            FROM fba_purchase_exception_policy_audit
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new PolicyHistory(r.getLong(7),(Boolean)r.getObject(1),
                r.getBoolean(2),(Integer)r.getObject(3),r.getInt(4),
                (Integer)r.getObject(5),r.getInt(6),
                id(r.getObject(8,UUID.class)),r.getString(9),
                date(r.getObject(10,OffsetDateTime.class))),tenant,marketplace);
    }
}
