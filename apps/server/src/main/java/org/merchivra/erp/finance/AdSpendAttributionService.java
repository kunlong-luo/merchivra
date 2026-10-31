package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Local manual Ads spend; NEVER represents Ads API authorization or verified cost. */
@Service
public class AdSpendAttributionService {
    private final JdbcTemplate jdbc;
    public AdSpendAttributionService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Source(String id,String sourceKey,String campaignReference,
        String businessDate,String spendAmount,String currency,String evidenceOrigin) {}
    public record Option(String id,String internalSku) {}
    public record Assignment(String skuId,String internalSku,int basisPoints,
        String evidenceReference,int revision,String approximateSpend) {}
    public record View(Source source,AdSpendAttributionEngine.Preview summary,
        List<Assignment> assignments) {}
    public record Audit(String skuId,String action,int basisPoints,
        String evidenceReference,int revision,String actorId,String occurredAt) {}
    private static ResponseStatusException bad(String reason){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    private static ResponseStatusException conflict(String reason){
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }
    private static String safe(String value,int minimum,String field) {
        if(value==null||value.length()<minimum||value.length()>160
            ||!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{"+(minimum-1)+",159}"))
            throw bad("Invalid "+field+" reference");
        return value;
    }
    private static String posted( java.sql.ResultSet r,String column )
        throws java.sql.SQLException {
        return r.getObject(column,OffsetDateTime.class).toInstant().toString();
    }
    private static Source sourceRow(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Source(r.getObject("id",UUID.class).toString(),
            r.getString("source_key"),r.getString("campaign_reference"),
            r.getObject("business_date",LocalDate.class).toString(),
            r.getBigDecimal("spend_amount").toPlainString(),
            r.getString("currency_code").trim(),r.getString("evidence_origin"));
    }
    private String marketplaceCurrency(UUID tenant,UUID mp) {
        var m=jdbc.query("""
            SELECT m.currency_code FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,(r,i)->r.getString(1).trim(),tenant,mp);
        if(m.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return m.getFirst();
    }
    private Source get(UUID tenant,UUID mp,UUID id,boolean lock) {
        var rows=jdbc.query("""
            SELECT * FROM finance_ad_spend_reference
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            AdSpendAttributionService::sourceRow,tenant,mp,id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    public List<Source> list(UUID tenant,UUID mp){
        marketplaceCurrency(tenant,mp);
        return jdbc.query("""
            SELECT * FROM finance_ad_spend_reference
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY business_date DESC,id DESC LIMIT 50
            """,AdSpendAttributionService::sourceRow,tenant,mp);
    }
    public List<Option> skuOptions(UUID tenant,UUID mp) {
        marketplaceCurrency(tenant,mp);
        return jdbc.query("""
            SELECT id,internal_sku FROM product_sku
            WHERE tenant_id=? AND status='ACTIVE'
            ORDER BY internal_sku,id LIMIT 200
            """,(r,i)->new Option(r.getObject(1,UUID.class).toString(),
                r.getString(2)),tenant);
    }
    @Transactional
    public Source create(UUID tenant,UUID mp,UUID actor,String key,String campaign,
        LocalDate date,String amountText,String currency) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        safe(key,8,"source");safe(campaign,1,"campaign");
        if(date==null||amountText==null
            ||!amountText.matches("[0-9]{1,14}(?:[.][0-9]{1,6})?"))
            throw bad("Business date and nonnegative original-currency spend required");
        BigDecimal amount=new BigDecimal(amountText);
        String code=marketplaceCurrency(tenant,mp);
        if(!code.equals(currency))throw conflict("Advertising reference must use Marketplace currency");
        // Immutable source and duplicate-key checks are serialized per Marketplace.
        jdbc.queryForList("""
            SELECT id FROM seller_marketplace WHERE tenant_id=? AND id=? FOR UPDATE
            """,UUID.class,tenant,mp);
        var existing=jdbc.query("""
            SELECT * FROM finance_ad_spend_reference
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_key=?
            """,AdSpendAttributionService::sourceRow,tenant,mp,key);
        if(!existing.isEmpty()){
            Source old=existing.getFirst();
            if(!old.campaignReference().equals(campaign)
               ||!old.businessDate().equals(date.toString())
               ||new BigDecimal(old.spendAmount()).compareTo(amount)!=0
               ||!old.currency().equals(currency))
                throw conflict("Same source key contains conflicting manually submitted facts");
            return old; // safe idempotent replay, no fabricated new duplicate
        }
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO finance_ad_spend_reference
                  (id,tenant_id,seller_marketplace_id,source_key,campaign_reference,
                   business_date,spend_amount,currency_code,created_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,id,tenant,mp,key,campaign,date,amount,currency,actor);
        } catch(DuplicateKeyException duplicate){
            throw conflict("Spend source reference already exists");
        }
        return get(tenant,mp,id,false);
    }
    private List<Assignment> assignments(UUID tenant,UUID sourceId,BigDecimal spend) {
        return jdbc.query("""
            SELECT a.product_sku_id,p.internal_sku,a.basis_points,
              a.evidence_reference,a.revision
            FROM finance_ad_spend_sku_attribution a
            JOIN product_sku p ON p.tenant_id=a.tenant_id AND p.id=a.product_sku_id
            WHERE a.tenant_id=? AND a.source_id=?
            ORDER BY p.internal_sku,a.product_sku_id LIMIT 201
            """,(r,i)->new Assignment(r.getObject(1,UUID.class).toString(),
                r.getString(2),r.getInt(3),r.getString(4),r.getInt(5),
                spend.multiply(BigDecimal.valueOf(r.getInt(3)))
                  .divide(BigDecimal.valueOf(10000),6,RoundingMode.HALF_UP)
                  .toPlainString()),tenant,sourceId);
    }
    private void audit(UUID tenant,UUID id,UUID actor,Assignment share,String action) {
        jdbc.update("""
            INSERT INTO finance_ad_spend_attribution_audit
              (id,tenant_id,source_id,product_sku_id,action,basis_points,
               evidence_reference,revision,actor_user_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,UUID.fromString(share.skuId()),
            action,share.basisPoints(),share.evidenceReference(),share.revision(),actor);
    }
    public View preview(UUID tenant,UUID mp,UUID id) {
        Source source=get(tenant,mp,id,false);
        var rows=assignments(tenant,id,new BigDecimal(source.spendAmount()));
        if(rows.size()>200)throw conflict("Too many SKU attribution rows");
        var share=rows.stream().map(r->new AdSpendAttributionEngine.Share(
            UUID.fromString(r.skuId()),r.basisPoints())).toList();
        var result=AdSpendAttributionEngine.evaluate(new BigDecimal(source.spendAmount()),
            source.currency(),share);
        return new View(source,result,List.copyOf(rows));
    }
    @Transactional
    public View assign(UUID tenant,UUID mp,UUID actor,UUID id,UUID sku,int points,
        String evidence) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        safe(evidence,8,"allocation evidence");
        if(points<1||points>10000)throw bad("Use basisPoints 1..10000");
        Source src=get(tenant,mp,id,true);
        Integer active=jdbc.queryForObject("""
            SELECT count(*) FROM product_sku
            WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,Integer.class,tenant,sku);
        if(active==null||active!=1)throw conflict("Tenant SKU not active");
        var before=assignments(tenant,id,new BigDecimal(src.spendAmount()));
        if(before.size()>200)throw conflict("Too many SKU allocations");
        var adjusted=new ArrayList<AdSpendAttributionEngine.Share>();
        for(Assignment current:before)
            if(!current.skuId().equals(sku.toString()))
                adjusted.add(new AdSpendAttributionEngine.Share(
                    UUID.fromString(current.skuId()),current.basisPoints()));
        adjusted.add(new AdSpendAttributionEngine.Share(sku,points));
        try {
            AdSpendAttributionEngine.evaluate(new BigDecimal(src.spendAmount()),
                src.currency(),adjusted);
        } catch(IllegalArgumentException invalid) {
            throw conflict("SKU allocation would exceed 100% or duplicate an item");
        }
        Assignment previous=before.stream()
            .filter(x->x.skuId().equals(sku.toString())).findFirst().orElse(null);
        if(previous!=null && previous.basisPoints()==points
            &&previous.evidenceReference().equals(evidence))return preview(tenant,mp,id);
        int revision=previous==null?1:previous.revision()+1;
        jdbc.update("""
            INSERT INTO finance_ad_spend_sku_attribution
              (tenant_id,source_id,product_sku_id,basis_points,evidence_reference,
               revision,reviewed_by)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,source_id,product_sku_id) DO UPDATE SET
              basis_points=EXCLUDED.basis_points,
              evidence_reference=EXCLUDED.evidence_reference,
              revision=EXCLUDED.revision,reviewed_by=EXCLUDED.reviewed_by,
              reviewed_at=now()
            """,tenant,id,sku,points,evidence,revision,actor);
        var record=new Assignment(sku.toString(),"",points,evidence,revision,null);
        audit(tenant,id,actor,record,previous==null?"CREATED":"UPDATED");
        return preview(tenant,mp,id);
    }
    @Transactional
    public void withdraw(UUID tenant,UUID mp,UUID actor,UUID id,UUID sku) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        get(tenant,mp,id,true);
        var old=jdbc.query("""
            DELETE FROM finance_ad_spend_sku_attribution
            WHERE tenant_id=? AND source_id=? AND product_sku_id=?
            RETURNING basis_points,evidence_reference,revision
            """,(r,i)->new Assignment(sku.toString(),"",r.getInt(1),r.getString(2),
                r.getInt(3),null),tenant,id,sku);
        if(old.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit(tenant,id,actor,old.getFirst(),"WITHDRAWN");
    }
    public List<Audit> history(UUID tenant,UUID mp,UUID id) {
        get(tenant,mp,id,false);
        return jdbc.query("""
            SELECT product_sku_id,action,basis_points,evidence_reference,
              revision,actor_user_id,occurred_at
            FROM finance_ad_spend_attribution_audit
            WHERE tenant_id=? AND source_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getObject(1,UUID.class).toString(),r.getString(2),
                r.getInt(3),r.getString(4),r.getInt(5),
                r.getObject(6,UUID.class).toString(),posted(r,"occurred_at")),
            tenant,id);
    }
}
