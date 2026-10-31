package org.merchivra.erp.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human-configured reorder advice on observed FBA fulfillable units only.
 * Does not treat UNKNOWN as 0, count unverified inbound receipts as available,
 * mutate Amazon resources, or auto-create purchase orders.
 */
@Service
public class ReplenishmentService {
    private static final Duration MAX_AGE=Duration.ofHours(48);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public ReplenishmentService(JdbcTemplate jdbc) {
        this(jdbc,Clock.systemUTC());
    }
    public ReplenishmentService(JdbcTemplate jdbc,Clock clock) {
        this.jdbc=jdbc;this.clock=clock;
    }
    public record Policy(String marketplaceId,String sellerSku,int reorderPoint,
        int targetStock,int leadTimeDays,String updatedAt) {}
    public record Advice(Policy policy,String observationId,String observationQuality,
        Integer fulfillable,Integer reserved,String observedAt,String state,Integer suggestedQuantity) {}
    public record Evaluation(String state,Integer suggestedQuantity) {}

    /** Shared, side-effect-free decision rule used by suggestions and purchase draft validation. */
    static Evaluation evaluate(String quality,Integer available,Integer reserved,
                               Instant observedAt,int reorderPoint,int targetStock,Instant now) {
        if(quality==null)return new Evaluation("NO_OBSERVATION",null);
        if(observedAt==null || observedAt.isBefore(now.minus(MAX_AGE))
           || observedAt.isAfter(now.plus(Duration.ofMinutes(5))))
            return new Evaluation("STALE_OBSERVATION",null);
        if(!"OBSERVED".equals(quality) || available==null || available<0
           || (reserved!=null && reserved<0))
            return new Evaluation("INCOMPLETE_OBSERVATION",null);
        if(available<=reorderPoint)
            return new Evaluation("REORDER",Math.max(0,targetStock-available));
        return new Evaluation("HEALTHY",null);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private static String sku(String raw) {
        if(raw==null || raw.isBlank() || raw.length()>200 ||
           raw.chars().anyMatch(Character::isISOControl))throw bad("Invalid Seller SKU");
        return raw;
    }
    private static void check(int point,int target,int lead) {
        if(point<0 || point>10_000_000 || target<=point || target>10_000_000
            || lead<1 || lead>365)throw bad("Invalid replenishment parameters");
    }
    private void requireMarketplace(UUID tenant,UUID marketplace) {
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace m JOIN tenant t
              ON t.id=m.tenant_id
            WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
            """,Integer.class,tenant,marketplace);
        if(count==null||count!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private static Policy map(java.sql.ResultSet rs,int i) throws java.sql.SQLException{
        return new Policy(rs.getObject("seller_marketplace_id",UUID.class).toString(),
            rs.getString("seller_sku"),rs.getInt("reorder_point"),
            rs.getInt("target_stock"),rs.getInt("lead_time_days"),
            rs.getObject("updated_at",OffsetDateTime.class).toInstant().toString());
    }
    public List<Policy> policies(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        return jdbc.query("""
            SELECT seller_marketplace_id,seller_sku,reorder_point,
              target_stock,lead_time_days,updated_at
            FROM erp_replenishment_policy
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY seller_sku LIMIT 500
            """,ReplenishmentService::map,tenant,marketplace);
    }
    @Transactional
    public Policy set(UUID tenant,UUID actor,UUID marketplace,String sellerSku,
                      int point,int target,int lead) {
        sellerSku=sku(sellerSku);
        check(point,target,lead);
        requireMarketplace(tenant,marketplace);
        List<String> found=jdbc.query("""
            SELECT seller_sku FROM marketplace_listing
            WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku=?
            FOR SHARE
            """,(rs,i)->rs.getString(1),tenant,marketplace,sellerSku);
        if(found.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Listing not available");
        jdbc.update("""
            INSERT INTO erp_replenishment_policy
              (tenant_id,seller_marketplace_id,seller_sku,
               reorder_point,target_stock,lead_time_days,updated_by)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,seller_marketplace_id,seller_sku) DO UPDATE SET
              reorder_point=EXCLUDED.reorder_point,target_stock=EXCLUDED.target_stock,
              lead_time_days=EXCLUDED.lead_time_days,updated_by=EXCLUDED.updated_by,
              updated_at=now()
            """,tenant,marketplace,sellerSku,point,target,lead,actor);
        audit(tenant,actor,marketplace,sellerSku,"POLICY_SET");
        return jdbc.query("""
            SELECT seller_marketplace_id,seller_sku,reorder_point,
              target_stock,lead_time_days,updated_at
            FROM erp_replenishment_policy
            WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku=?
            """,ReplenishmentService::map,tenant,marketplace,sellerSku).getFirst();
    }
    @Transactional
    public void remove(UUID tenant,UUID actor,UUID marketplace,String sellerSku) {
        requireMarketplace(tenant,marketplace);
        int count=jdbc.update("""
            DELETE FROM erp_replenishment_policy
            WHERE tenant_id=? AND seller_marketplace_id=? AND seller_sku=?
            """,tenant,marketplace,sku(sellerSku));
        if(count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit(tenant,actor,marketplace,sellerSku,"POLICY_REMOVED");
    }
    public List<Advice> advice(UUID tenant,UUID marketplace) {
        requireMarketplace(tenant,marketplace);
        record Observed(Policy policy,UUID observationId,String quality,Integer available,
                        Integer reserved,Instant when) {}
        List<Observed> rows=jdbc.query("""
            SELECT p.seller_marketplace_id,p.seller_sku,p.reorder_point,
                p.target_stock,p.lead_time_days,p.updated_at,
                f.id AS observation_id,f.quality,f.fulfillable,f.reserved,f.observed_at
            FROM erp_replenishment_policy p
            LEFT JOIN LATERAL (
              SELECT id,quality,fulfillable,reserved,observed_at
              FROM fba_inventory_observation
              WHERE tenant_id=p.tenant_id
                AND seller_marketplace_id=p.seller_marketplace_id
                AND seller_sku_raw=p.seller_sku
              ORDER BY observed_at DESC NULLS LAST,ingested_at DESC,id DESC
              LIMIT 1
            ) f ON TRUE
            WHERE p.tenant_id=? AND p.seller_marketplace_id=?
            ORDER BY p.seller_sku LIMIT 500
            """,(rs,i)->{
              Integer units=(Integer)rs.getObject("fulfillable");
              Integer reserved=(Integer)rs.getObject("reserved");
              OffsetDateTime observed=rs.getObject("observed_at",OffsetDateTime.class);
              return new Observed(map(rs,i),rs.getObject("observation_id",UUID.class),
                  rs.getString("quality"),units,reserved,
                  observed==null?null:observed.toInstant());
            },tenant,marketplace);
        Instant now=clock.instant();
        return rows.stream().map(r->{
            Evaluation evaluation=evaluate(r.quality(),r.available(),r.reserved(),r.when(),
                r.policy().reorderPoint(),r.policy().targetStock(),now);
            return new Advice(r.policy(),
                r.observationId()==null?null:r.observationId().toString(),
                r.quality(),r.available(),r.reserved(),
                r.when()==null?null:r.when().toString(),
                evaluation.state(),evaluation.suggestedQuantity());
        }).toList();
    }
    private void audit(UUID tenant,UUID actor,UUID marketplace,String sellerSku,String action){
        jdbc.update("""
            INSERT INTO erp_replenishment_policy_audit
              (id,tenant_id,seller_marketplace_id,seller_sku,actor_user_id,action)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,marketplace,sellerSku,actor,action);
    }
}
