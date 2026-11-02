package org.merchivra.erp.operations;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.merchivra.erp.fba.FbaPurchaseExceptionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** V82. Advisory-only escalation with immutable source risk floors. */
@Service
public class OperationsExceptionPriorityService {
    private final JdbcTemplate jdbc;
    private final FbaPurchaseExceptionService cases;
    private static final Map<String,String> MINIMUM=Map.of(
        "SOURCE_CHANGED","P0","PAST_DUE","P0",
        "INDEPENDENT_IDENTITY_REVIEW","P1","LOCAL_VARIANCE_REVIEW","P1",
        "DUE_SOON","P1","HIGH_SEVERITY","P1",
        "NEEDS_OWNER","P2","SOURCE_WARNING","P2","ROUTINE_FOLLOWUP","P3");
    private static final List<String> CODES=List.of(
        "SOURCE_CHANGED","PAST_DUE","INDEPENDENT_IDENTITY_REVIEW",
        "LOCAL_VARIANCE_REVIEW","DUE_SOON","HIGH_SEVERITY","NEEDS_OWNER",
        "SOURCE_WARNING","ROUTINE_FOLLOWUP");

    public OperationsExceptionPriorityService(JdbcTemplate jdbc,
                                               FbaPurchaseExceptionService cases) {
        this.jdbc=jdbc;this.cases=cases;
    }
    public record Rule(String reasonCode,String minimumPriority,
                       String priority,long version,boolean customized) {}
    public record Change(String priority,long expectedVersion,String note) {}
    public record Audit(String reasonCode,String oldPriority,String newPriority,
                        long oldVersion,long newVersion,String actorId,
                        String note,String occurredAt) {}

    public static String validatePriority(String reasonCode,String priority) {
        String floor=MINIMUM.get(reasonCode);
        if(floor==null || priority==null || !priority.matches("P[0-3]"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported reason/priority");
        // Lower ordinal = more urgent. Never downgrade source drift, overdue,
        // mandatory identity or independent variance evidence review.
        if(priority.compareTo(floor)>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot downgrade mandatory evidence priority");
        return priority;
    }
    @Transactional(readOnly=true)
    public List<Rule> rules(UUID tenant,UUID marketplace) {
        cases.metrics(tenant,marketplace); // fail closed on foreign/inactive site
        Map<String,Rule> saved=new java.util.HashMap<>();
        jdbc.query("""
            SELECT reason_code,priority,version
            FROM fba_exception_priority_setting
            WHERE tenant_id=? AND seller_marketplace_id=?
            """,r->{
                String code=r.getString("reason_code");
                saved.put(code,new Rule(code,MINIMUM.get(code),
                    r.getString("priority"),r.getLong("version"),true));
            },tenant,marketplace);
        return CODES.stream().map(code->saved.getOrDefault(code,
            new Rule(code,MINIMUM.get(code),MINIMUM.get(code),0,false))).toList();
    }
    @Transactional
    public Rule save(UUID tenant,UUID marketplace,UUID actor,
                     String reason,Change input) {
        if(input==null || input.expectedVersion()<0 || input.note()==null
           ||input.note().strip().length()<10 ||input.note().strip().length()>300
           ||input.note().chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Note must have 10..300 printable characters");
        String priority=validatePriority(reason,input.priority());
        cases.metrics(tenant,marketplace);
        // Same explicit tenant+marketplace lock discipline used in V77 batches.
        jdbc.queryForObject("""
            SELECT id FROM seller_marketplace
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,marketplace);
        var existing=jdbc.query("""
            SELECT priority,version FROM fba_exception_priority_setting
            WHERE tenant_id=? AND seller_marketplace_id=? AND reason_code=?
            FOR UPDATE
            """,(r,i)->new Object[]{r.getString("priority"),r.getLong("version")},
            tenant,marketplace,reason);
        long current=existing.isEmpty()?0:(Long)existing.getFirst()[1];
        if(current!=input.expectedVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Priority setting changed by another Owner");
        long next=current+1;
        if(existing.isEmpty()) {
            jdbc.update("""
                INSERT INTO fba_exception_priority_setting(
                  tenant_id,seller_marketplace_id,reason_code,priority,version,updated_by)
                VALUES(?,?,?,?,?,?)
                """,tenant,marketplace,reason,priority,next,actor);
        } else {
            jdbc.update("""
                UPDATE fba_exception_priority_setting
                   SET priority=?,version=?,updated_by=?,updated_at=now()
                 WHERE tenant_id=? AND seller_marketplace_id=? AND reason_code=?
                """,priority,next,actor,tenant,marketplace,reason);
        }
        jdbc.update("""
            INSERT INTO fba_exception_priority_audit(
             id,tenant_id,seller_marketplace_id,reason_code,old_priority,
             new_priority,old_version,new_version,actor_user_id,note)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,marketplace,reason,
            existing.isEmpty()?null:existing.getFirst()[0],
            priority,current,next,actor,input.note().strip());
        return new Rule(reason,MINIMUM.get(reason),priority,next,true);
    }
    @Transactional(readOnly=true)
    public List<Audit> audit(UUID tenant,UUID marketplace) {
        cases.metrics(tenant,marketplace);
        return jdbc.query("""
            SELECT reason_code,old_priority,new_priority,old_version,new_version,
                   actor_user_id,note,occurred_at
            FROM fba_exception_priority_audit
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString("reason_code"),
                r.getString("old_priority"),r.getString("new_priority"),
                r.getLong("old_version"),r.getLong("new_version"),
                r.getObject("actor_user_id",UUID.class).toString(),
                r.getString("note"),
                r.getObject("occurred_at",java.time.OffsetDateTime.class)
                    .toInstant().toString()),tenant,marketplace);
    }
}
