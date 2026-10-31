package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-reviewed interpretation dictionary, NOT a financial posting engine.
 * Never updates finance_source_event or finance_transaction_breakdown.
 */
@Service
public class FinanceClassificationService {
    private static final Set<String> CATEGORIES=Set.of("PRODUCT_SALES",
        "PRODUCT_REFUND","AMAZON_FEE","FBA_FEE","ADJUSTMENT","UNCLASSIFIED");
    private final JdbcTemplate jdbc;
    private final FinancesBreakdownService breakdowns;
    public FinanceClassificationService(JdbcTemplate jdbc,FinancesBreakdownService breakdowns) {
        this.jdbc=jdbc;this.breakdowns=breakdowns;
    }
    public record Rule(String id,String sourceType,String category,String state,
                       int revision,String updatedAt) {}
    public record Audit(String action,String previousCategory,String newCategory,
                        String previousState,String newState,int revision,
                        String actorId,String occurredAt) {}
    public record EventPreview(String eventId,String sourceFamily,String dataOrigin,
                               String status,FinanceClassificationEngine.Preview analysis) {}

    private static String validType(String value) {
        if(value==null || value.isBlank() || value.length()>160
           || value.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact printable source breakdown type is required (max 160)");
        return value;
    }
    private static String validCategory(String category) {
        if(!CATEGORIES.contains(category))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid category");
        return category;
    }
    private void marketplace(UUID tenant,UUID mp) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM seller_marketplace m JOIN tenant t ON t.id=m.tenant_id
            WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
            """,Integer.class,tenant,mp);
        if(count==null||count!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Marketplace not found");
    }
    private static String time(ResultSet rs,String name)throws SQLException{
        var at=rs.getObject(name,OffsetDateTime.class);
        return at==null?null:at.toInstant().toString();
    }
    private static Rule rule(ResultSet rs,int index)throws SQLException {
        return new Rule(rs.getObject("id",UUID.class).toString(),
            rs.getString("source_type"),rs.getString("category"),rs.getString("state"),
            rs.getInt("revision"),time(rs,"updated_at"));
    }
    public List<Rule> rules(UUID tenant,UUID mp) {
        marketplace(tenant,mp);
        return jdbc.query("""
            SELECT id,source_type,category,state,revision,updated_at
            FROM finance_breakdown_rule WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY source_type,id LIMIT 500
            """,FinanceClassificationService::rule,tenant,mp);
    }
    private void audit(UUID tenant,UUID id,UUID actor,String action,
        Rule previous,String category,String state,int revision) {
        jdbc.update("""
            INSERT INTO finance_breakdown_rule_audit
              (id,tenant_id,rule_id,actor_id,action,previous_category,new_category,
               previous_state,new_state,revision)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,actor,action,
            previous==null?null:previous.category(),category,
            previous==null?null:previous.state(),state,revision);
    }
    /**
     * The Marketplace row lock serializes competing creates and updates before
     * Rule uniqueness is tested. React cannot impersonate reviewer/tenant.
     */
    @Transactional
    public Rule upsert(UUID tenant,UUID mp,UUID actor,String sourceType,String category) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        validType(sourceType);validCategory(category);
        marketplace(tenant,mp);
        jdbc.queryForList("""
            SELECT id FROM seller_marketplace WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,mp);
        List<Rule> existing=jdbc.query("""
            SELECT id,source_type,category,state,revision,updated_at
            FROM finance_breakdown_rule
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_type=?
            FOR UPDATE
            """,FinanceClassificationService::rule,tenant,mp,sourceType);
        if(existing.isEmpty()) {
            UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finance_breakdown_rule
                  (id,tenant_id,seller_marketplace_id,source_type,
                   category,state,revision,updated_by)
                VALUES (?,?,?,?,?,'ACTIVE',1,?)
                """,id,tenant,mp,sourceType,category,actor);
            audit(tenant,id,actor,"CREATED",null,category,"ACTIVE",1);
            return get(tenant,mp,id);
        }
        Rule before=existing.getFirst();
        if("ACTIVE".equals(before.state())&&category.equals(before.category()))return before;
        int revision=before.revision()+1;
        UUID id=UUID.fromString(before.id());
        jdbc.update("""
            UPDATE finance_breakdown_rule
            SET category=?,state='ACTIVE',revision=?,updated_by=?,updated_at=now()
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,category,revision,actor,tenant,mp,id);
        audit(tenant,id,actor,"CHANGED",before,category,"ACTIVE",revision);
        return get(tenant,mp,id);
    }
    private Rule get(UUID tenant,UUID mp,UUID id) {
        return jdbc.query("""
            SELECT id,source_type,category,state,revision,updated_at
            FROM finance_breakdown_rule
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,FinanceClassificationService::rule,tenant,mp,id)
            .stream().findFirst().orElseThrow(()->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    public Rule deactivate(UUID tenant,UUID mp,UUID actor,UUID id) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        marketplace(tenant,mp);
        jdbc.queryForList("""
            SELECT id FROM seller_marketplace WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,mp);
        var list=jdbc.query("""
            SELECT id,source_type,category,state,revision,updated_at
            FROM finance_breakdown_rule
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=? FOR UPDATE
            """,FinanceClassificationService::rule,tenant,mp,id);
        if(list.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Rule before=list.getFirst();
        if("INACTIVE".equals(before.state()))return before;
        int next=before.revision()+1;
        jdbc.update("""
            UPDATE finance_breakdown_rule
            SET state='INACTIVE',revision=?,updated_by=?,updated_at=now()
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,next,actor,tenant,mp,id);
        audit(tenant,id,actor,"DEACTIVATED",before,before.category(),"INACTIVE",next);
        return get(tenant,mp,id);
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID ruleId) {
        marketplace(tenant,mp);
        get(tenant,mp,ruleId);
        return jdbc.query("""
            SELECT a.* FROM finance_breakdown_rule_audit a
            JOIN finance_breakdown_rule r ON r.tenant_id=a.tenant_id AND r.id=a.rule_id
            WHERE a.tenant_id=? AND r.seller_marketplace_id=? AND a.rule_id=?
            ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100
            """,(rs,n)->new Audit(rs.getString("action"),
                rs.getString("previous_category"),rs.getString("new_category"),
                rs.getString("previous_state"),rs.getString("new_state"),
                rs.getInt("revision"),rs.getObject("actor_id",UUID.class).toString(),
                time(rs,"occurred_at")),tenant,mp,ruleId);
    }
    public EventPreview preview(UUID tenant,UUID mp,UUID eventId) {
        marketplace(tenant,mp);
        record Header(String family,String origin,BigDecimal amount,String currency){}
        List<Header> rows=jdbc.query("""
            SELECT source_family,data_origin,amount,currency_code
            FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,(rs,n)->new Header(rs.getString(1),rs.getString(2),
                rs.getBigDecimal(3),rs.getString(4).trim()),tenant,mp,eventId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Header header=rows.getFirst();
        if(!"SPAPI_FINANCES".equals(header.family())||!"LIVE".equals(header.origin()))
            return new EventPreview(eventId.toString(),header.family(),
                header.origin(),"MANUAL_NOT_ELIGIBLE",null);
        var nodes=breakdowns.list(tenant,mp,eventId);
        Map<String,String> active=new HashMap<>();
        for(Rule rule:rules(tenant,mp))
            if("ACTIVE".equals(rule.state()))active.put(rule.sourceType(),rule.category());
        var leaves=nodes.stream().filter(FinancesBreakdownService.Node::leaf)
            .map(n->new FinanceClassificationEngine.Leaf(n.path(),n.sourceType(),
                n.amount()==null?null:new BigDecimal(n.amount()),n.currency()))
            .toList();
        var calculated=FinanceClassificationEngine.evaluate(header.amount(),
            header.currency(),leaves,active);
        return new EventPreview(eventId.toString(),header.family(),header.origin(),
            calculated.coverage(),calculated);
    }
}
