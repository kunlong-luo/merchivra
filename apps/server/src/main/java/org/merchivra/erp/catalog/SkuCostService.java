package org.merchivra.erp.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Original-currency, date-versioned unit costs; NEVER called financial settlement. */
@Service
public class SkuCostService {
    private final JdbcTemplate jdbc;
    public SkuCostService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Cost(String id,String productSkuId,String unitCost,String currency,
       String effectiveFrom,String effectiveTo,String createdAt){}

    private static Cost row(java.sql.ResultSet r,int n) throws java.sql.SQLException{
        return new Cost(r.getObject("id",UUID.class).toString(),
            r.getObject("product_sku_id",UUID.class).toString(),
            r.getBigDecimal("unit_cost").toPlainString(),r.getString("currency_code").trim(),
            r.getObject("effective_from",LocalDate.class).toString(),
            r.getObject("effective_to",LocalDate.class)==null?null:
              r.getObject("effective_to",LocalDate.class).toString(),
            r.getObject("created_at",OffsetDateTime.class).toInstant().toString());
    }
    public List<Cost> history(UUID tenant,UUID sku) {
        return jdbc.query("""
          SELECT id,product_sku_id,unit_cost,currency_code,effective_from,effective_to,created_at
          FROM sku_cost_version WHERE tenant_id=? AND product_sku_id=?
          ORDER BY effective_from DESC LIMIT 500
          """,SkuCostService::row,tenant,sku);
    }

    @Transactional
    public Cost add(UUID tenant,UUID actor,UUID sku,String amount,String currency,
                    LocalDate from) {
        if(amount==null || !amount.matches("[0-9]{1,14}(?:\\.[0-9]{1,6})?")
           || currency==null || !currency.matches("[A-Z]{3}") || from==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid unit cost or date");
        BigDecimal value=new BigDecimal(amount);
        List<UUID> locked=jdbc.query("""
          SELECT id FROM product_sku WHERE tenant_id=? AND id=? AND status='ACTIVE'
          FOR UPDATE
          """,(rs,n)->rs.getObject(1,UUID.class),tenant,sku);
        if(locked.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Active internal SKU not found");
        record Open(UUID id,LocalDate from) {}
        List<Open> existing=jdbc.query("""
          SELECT id,effective_from FROM sku_cost_version
          WHERE tenant_id=? AND product_sku_id=? AND effective_to IS NULL FOR UPDATE
          """,(rs,n)->new Open(rs.getObject(1,UUID.class),
              rs.getObject(2,LocalDate.class)),tenant,sku);
        if(existing.size()>1)throw new IllegalStateException("Overlapping cost history");
        if(!existing.isEmpty()) {
            Open previous=existing.getFirst();
            if(!from.isAfter(previous.from()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "New cost date must be later than existing open cost");
            jdbc.update("""
              UPDATE sku_cost_version SET effective_to=?
              WHERE tenant_id=? AND id=? AND effective_to IS NULL
              """,from,tenant,previous.id());
        } else {
            Integer count=jdbc.queryForObject("""
              SELECT COUNT(*) FROM sku_cost_version
              WHERE tenant_id=? AND product_sku_id=? AND effective_from>=?
              """,Integer.class,tenant,sku,from);
            if(count!=null && count>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cost history cannot overlap existing periods");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
          INSERT INTO sku_cost_version
            (id,tenant_id,product_sku_id,unit_cost,currency_code,effective_from,created_by)
          VALUES (?,?,?,?,?,?,?)
          """,id,tenant,sku,value,currency,from,actor);
        return jdbc.query("""
          SELECT id,product_sku_id,unit_cost,currency_code,effective_from,effective_to,created_at
          FROM sku_cost_version WHERE tenant_id=? AND id=?
          """,SkuCostService::row,tenant,id).getFirst();
    }
}
