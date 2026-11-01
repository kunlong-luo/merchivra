package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only comparison of independent source facts, NEVER ledger posting.
 * Finances linkage requires exact settlement ID, tenant, marketplace and
 * currency; amounts without an identifier are not treated as evidence.
 */
@Service
public class SettlementReportComparisonService {
    private final JdbcTemplate jdbc;
    public SettlementReportComparisonService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Comparison(String reportId,String settlementId,String currency,
        String reportedNet,String reportLinesNet,String financesNet,
        long financeTransactionCount,long otherCurrencyTransactionCount,
        String state,boolean reconciled,boolean bankVerified) {}

    static final String COMPARISON_SQL="""
        SELECT r.amazon_report_id,r.settlement_reference,r.statement_currency,
          r.declared_net,
          (SELECT SUM(line.signed_amount) FROM spapi_settlement_report_line line
           WHERE line.tenant_id=r.tenant_id AND line.report_id=r.id) AS line_net,
          (SELECT SUM(f.amount)
           FROM finance_transaction_reference ref
           JOIN finance_source_event f ON f.tenant_id=ref.tenant_id
             AND f.id=ref.event_id
           WHERE ref.tenant_id=r.tenant_id
             AND ref.reference_kind='SETTLEMENT_ID'
             AND ref.reference_value=r.settlement_reference
             AND f.seller_marketplace_id=r.seller_marketplace_id
             AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
             AND f.currency_code=r.statement_currency) AS finance_net,
          (SELECT count(*)
           FROM finance_transaction_reference ref
           JOIN finance_source_event f ON f.tenant_id=ref.tenant_id
             AND f.id=ref.event_id
           WHERE ref.tenant_id=r.tenant_id
             AND ref.reference_kind='SETTLEMENT_ID'
             AND ref.reference_value=r.settlement_reference
             AND f.seller_marketplace_id=r.seller_marketplace_id
             AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
             AND f.currency_code=r.statement_currency) AS matched_count,
          (SELECT count(*)
           FROM finance_transaction_reference ref
           JOIN finance_source_event f ON f.tenant_id=ref.tenant_id
             AND f.id=ref.event_id
           WHERE ref.tenant_id=r.tenant_id
             AND ref.reference_kind='SETTLEMENT_ID'
             AND ref.reference_value=r.settlement_reference
             AND f.seller_marketplace_id=r.seller_marketplace_id
             AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
             AND f.currency_code<>r.statement_currency) AS foreign_count
        FROM spapi_settlement_report r
        WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?
        """;

    public Comparison compare(UUID tenant,UUID marketplace,UUID reportId) {
        List<Comparison> result=jdbc.query(COMPARISON_SQL,(rs,n)->{
            BigDecimal declared=rs.getBigDecimal("declared_net");
            BigDecimal line=rs.getBigDecimal("line_net");
            BigDecimal finance=rs.getBigDecimal("finance_net");
            long count=rs.getLong("matched_count");
            long other=rs.getLong("foreign_count");
            return new Comparison(rs.getString("amazon_report_id"),
                rs.getString("settlement_reference"),
                rs.getString("statement_currency").trim(),
                money(declared),money(line),money(finance),
                count,other,decision(declared,line,finance,count,other),
                false,false);
        },tenant,marketplace,reportId);
        return result.stream().findFirst().orElseThrow(()->
            new ResponseStatusException(HttpStatus.NOT_FOUND,"Report not available"));
    }

    static String decision(BigDecimal declared,BigDecimal detail,
        BigDecimal finances,long count,long otherCurrency) {
        if(detail==null)return "REPORT_DETAIL_INCOMPLETE";
        if(declared!=null && declared.compareTo(detail)!=0)
            return "REPORT_INTERNAL_DIFFERENCE";
        if(otherCurrency>0)return "CURRENCY_CONFLICT";
        if(count==0 || finances==null)return "NO_FINANCES_SETTLEMENT_REFERENCE";
        if(detail.compareTo(finances)!=0)return "SOURCE_TOTAL_DIFFERENCE";
        return "SOURCE_AMOUNTS_AGREE_UNVERIFIED";
    }
    private static String money(BigDecimal value) {
        return value==null?null:value.toPlainString();
    }
}
