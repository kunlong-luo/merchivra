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
        long expectedReportLineCount,long actualReportLineCount,
        long foreignCurrencyReportLineCount,long multiplyReferencedFinancesEventCount,
        long otherReportSameSettlementCount,
        String state,boolean reconciled,boolean bankVerified) {}

    static final String COMPARISON_SQL="""
        SELECT r.amazon_report_id,r.settlement_reference,r.statement_currency,
          r.declared_net,r.line_count AS expected_line_count,
          (SELECT count(*) FROM spapi_settlement_report_line entry
           WHERE entry.tenant_id=r.tenant_id
             AND entry.report_id=r.id) AS actual_line_count,
          (SELECT count(*) FROM spapi_settlement_report_line entry
           WHERE entry.tenant_id=r.tenant_id AND entry.report_id=r.id
             AND entry.currency_code<>r.statement_currency) AS foreign_line_count,
          (SELECT count(*) FROM spapi_settlement_report competing
           WHERE competing.tenant_id=r.tenant_id
             AND competing.seller_marketplace_id=r.seller_marketplace_id
             AND competing.settlement_reference=r.settlement_reference
             AND competing.id<>r.id) AS shared_report_count,
          (SELECT count(*) FROM finance_transaction_reference ref
           JOIN finance_source_event f ON f.tenant_id=ref.tenant_id
             AND f.id=ref.event_id
           WHERE ref.tenant_id=r.tenant_id
             AND ref.reference_kind='SETTLEMENT_ID'
             AND ref.reference_value=r.settlement_reference
             AND f.seller_marketplace_id=r.seller_marketplace_id
             AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
             AND EXISTS (
               SELECT 1 FROM finance_transaction_reference other
               WHERE other.tenant_id=ref.tenant_id
                 AND other.event_id=ref.event_id
                 AND other.reference_kind='SETTLEMENT_ID'
                 AND other.reference_value<>ref.reference_value
             )) AS multi_settlement_event_count,
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
            long expected=rs.getLong("expected_line_count");
            long actual=rs.getLong("actual_line_count");
            long foreignLines=rs.getLong("foreign_line_count");
            long multiRefs=rs.getLong("multi_settlement_event_count");
            long sharedReports=rs.getLong("shared_report_count");
            return new Comparison(rs.getString("amazon_report_id"),
                rs.getString("settlement_reference"),
                rs.getString("statement_currency").trim(),
                money(declared),money(line),money(finance),
                count,other,expected,actual,foreignLines,multiRefs,sharedReports,
                decision(declared,line,finance,count,other,expected,actual,
                    foreignLines,multiRefs,sharedReports),false,false);
        },tenant,marketplace,reportId);
        return result.stream().findFirst().orElseThrow(()->
            new ResponseStatusException(HttpStatus.NOT_FOUND,"Report not available"));
    }

    /**
     * Report import and Finances document references are local metadata, not
     * independent payment authorization. A consistent total is not enough:
     * count/currency defects and competing settlements must win over equality.
     * The caller must supply ALL whole-scope counts from one SQL snapshot.
     */
    static String decision(BigDecimal declared,BigDecimal detail,
        BigDecimal finances,long count,long otherCurrency,
        long expectedLines,long actualLines,long foreignLines,
        long multiSettlementEvents,long sharedReports) {
        if(expectedLines<1||actualLines!=expectedLines)
            return "REPORT_LINE_COUNT_MISMATCH";
        if(foreignLines>0)return "REPORT_LINE_CURRENCY_CONFLICT";
        if(multiSettlementEvents>0)
            return "FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM";
        if(sharedReports>0)
            return "SETTLEMENT_REFERENCE_SHARED_REPORTS";
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
