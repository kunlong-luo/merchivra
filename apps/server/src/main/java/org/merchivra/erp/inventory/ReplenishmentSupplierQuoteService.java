package org.merchivra.erp.inventory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.merchivra.erp.purchasing.SupplierPurchasePlanService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V71 read-only FBA supplier options. Reuses V22 FBA observation gate, V42
 * supplier SKU terms and carton rounding. Never makes a purchase or assumes
 * that reported supplier lead time is a confirmed FBA arrival time.
 */
@Service
public class ReplenishmentSupplierQuoteService {
    private final JdbcTemplate jdbc;
    private final ReplenishmentService replenishment;
    public ReplenishmentSupplierQuoteService(JdbcTemplate jdbc,ReplenishmentService replenishment) {
        this.jdbc=jdbc;this.replenishment=replenishment;
    }
    public record Quote(String sellerSku,String productSkuId,String supplierId,
                        String supplierName,String supplierRuleUpdatedAt,
                        String observationId,String policyUpdatedAt,
                        int baseSuggestedQuantity,int minimumOrderQuantity,
                        int packMultiple,int supplierLeadTimeDays,
                        String currency,String unitCost,String status,
                        Integer orderQuantity,String estimatedTotal) {}
    private record Terms(String sku,UUID productSku,UUID supplier,String supplierName,
                         String ruleUpdatedAt,int minimum,int pack,int days,
                         String currency,BigDecimal unitCost) {}
    private static Terms map(ResultSet rs,int i) throws SQLException {
        return new Terms(rs.getString("seller_sku"),
            rs.getObject("product_sku_id",UUID.class),
            rs.getObject("supplier_id",UUID.class),rs.getString("supplier_name"),
            rs.getObject("rule_updated_at",OffsetDateTime.class).toInstant().toString(),
            rs.getInt("minimum_order_quantity"),rs.getInt("pack_multiple"),
            rs.getInt("lead_time_days"),rs.getString("currency_code").trim(),
            rs.getBigDecimal("unit_cost"));
    }
    @Transactional(readOnly=true)
    public List<Quote> quotes(UUID tenant,UUID marketplace) {
        // Validates active marketplace and uses the exact existing 48h OBSERVED
        // FBA logic; a supplier rule alone must never manufacture missing stock.
        Map<String,ReplenishmentService.Advice> bySku=new HashMap<>();
        for(var advice:replenishment.advice(tenant,marketplace))
            bySku.put(advice.policy().sellerSku(),advice);
        return jdbc.query("""
            SELECT p.seller_sku,m.product_sku_id,r.supplier_id,
                   sup.display_name AS supplier_name,
                   r.updated_at AS rule_updated_at,r.minimum_order_quantity,
                   r.pack_multiple,r.lead_time_days,r.currency_code,r.unit_cost
            FROM erp_replenishment_policy p
            JOIN marketplace_listing l
              ON l.tenant_id=p.tenant_id AND l.seller_marketplace_id=p.seller_marketplace_id
              AND l.seller_sku=p.seller_sku AND l.mapping_status='ACTIVE'
            JOIN listing_sku_mapping m
              ON m.tenant_id=l.tenant_id AND m.listing_id=l.id
              AND m.status='ACTIVE' AND m.valid_to IS NULL
            JOIN product_sku sku
              ON sku.tenant_id=m.tenant_id AND sku.id=m.product_sku_id
              AND sku.status='ACTIVE'
            JOIN erp_supplier_sku_rule r
              ON r.tenant_id=m.tenant_id AND r.product_sku_id=m.product_sku_id
            JOIN erp_supplier sup
              ON sup.tenant_id=r.tenant_id AND sup.id=r.supplier_id
              AND sup.status='ACTIVE'
            WHERE p.tenant_id=? AND p.seller_marketplace_id=?
            ORDER BY p.seller_sku,sup.display_name,r.supplier_id LIMIT 500
            """,ReplenishmentSupplierQuoteService::map,tenant,marketplace).stream().map(t->{
                var a=bySku.get(t.sku());
                String state=a==null?"NO_POLICY":a.state();
                Integer base=a==null?null:a.suggestedQuantity();
                Integer rounded=null;
                String amount=null;
                if("REORDER".equals(state) && base!=null && base>0) {
                    try {
                        rounded=Math.toIntExact(SupplierPurchasePlanService.orderQuantity(
                            base,t.minimum(),t.pack()));
                        amount=t.unitCost().multiply(BigDecimal.valueOf(rounded)).toPlainString();
                        state="READY";
                    } catch(ResponseStatusException | ArithmeticException e) {
                        state="LIMIT_EXCEEDED";
                    }
                }
                return new Quote(t.sku(),t.productSku().toString(),t.supplier().toString(),
                    t.supplierName(),t.ruleUpdatedAt(),
                    a==null?null:a.observationId(),
                    a==null?null:a.policy().updatedAt(),
                    base==null?0:base,t.minimum(),t.pack(),t.days(),
                    t.currency(),t.unitCost().toPlainString(),state,rounded,amount);
            }).toList();
    }
}
