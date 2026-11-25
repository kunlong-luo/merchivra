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
          -- V271: even if the settlement total agrees, exact raw order
          -- reference buckets (including the NULL bucket) must not offset.
          -- UNION ALL avoids a null/empty sentinel or many-to-many row join.
          EXISTS (
            SELECT 1 FROM (
              SELECT line.external_order_id AS order_ref,
                line.signed_amount AS report_amount,NULL::numeric AS finance_amount
              FROM spapi_settlement_report_line line
              WHERE line.tenant_id=r.tenant_id AND line.report_id=r.id
                AND line.currency_code=r.statement_currency
              UNION ALL
              SELECT f.external_order_id,NULL::numeric,f.amount
              FROM finance_transaction_reference ref
              JOIN finance_source_event f ON f.tenant_id=ref.tenant_id AND f.id=ref.event_id
              WHERE ref.tenant_id=r.tenant_id AND ref.reference_kind='SETTLEMENT_ID'
                AND ref.reference_value=r.settlement_reference
                AND f.seller_marketplace_id=r.seller_marketplace_id
                AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
                AND f.currency_code=r.statement_currency
            ) bucket_rows
            GROUP BY order_ref
            HAVING count(report_amount)=0 OR count(finance_amount)=0
              OR sum(report_amount) IS DISTINCT FROM sum(finance_amount)
          ) AS order_bucket_conflict,
          -- V272: transaction totals may net several fee and income leaves.
          -- Never compare the sign of a net transaction to report line signs.
          EXISTS (
            SELECT 1 FROM finance_transaction_reference ref
            JOIN finance_source_event f ON f.tenant_id=ref.tenant_id AND f.id=ref.event_id
            LEFT JOIN LATERAL (
              SELECT count(*) AS leaf_count,count(b.original_amount) AS amount_count,
                count(*) FILTER (WHERE b.currency_code=f.currency_code) AS currency_count,
                sum(b.original_amount) AS leaf_net
              FROM finance_transaction_breakdown b
              WHERE b.tenant_id=f.tenant_id AND b.event_id=f.id AND b.is_leaf
            ) leaves ON true
            WHERE ref.tenant_id=r.tenant_id AND ref.reference_kind='SETTLEMENT_ID'
              AND ref.reference_value=r.settlement_reference
              AND f.seller_marketplace_id=r.seller_marketplace_id
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND f.currency_code=r.statement_currency
              AND (leaves.leaf_count NOT BETWEEN 1 AND 2000
                OR leaves.amount_count<>leaves.leaf_count
                OR leaves.currency_count<>leaves.leaf_count
                OR leaves.leaf_net IS DISTINCT FROM f.amount)
          ) AS leaf_coverage_conflict,
          EXISTS (
            SELECT 1 FROM (
              SELECT line.external_order_id AS order_ref,sign(line.signed_amount) AS direction,
                line.signed_amount AS report_amount,NULL::numeric AS finance_amount
              FROM spapi_settlement_report_line line
              WHERE line.tenant_id=r.tenant_id AND line.report_id=r.id
                AND line.currency_code=r.statement_currency
              UNION ALL
              SELECT f.external_order_id,sign(b.original_amount),NULL::numeric,b.original_amount
              FROM finance_transaction_reference ref
              JOIN finance_source_event f ON f.tenant_id=ref.tenant_id AND f.id=ref.event_id
              JOIN finance_transaction_breakdown b ON b.tenant_id=f.tenant_id AND b.event_id=f.id
                AND b.is_leaf AND b.currency_code=f.currency_code
              WHERE ref.tenant_id=r.tenant_id AND ref.reference_kind='SETTLEMENT_ID'
                AND ref.reference_value=r.settlement_reference
                AND f.seller_marketplace_id=r.seller_marketplace_id
                AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
                AND f.currency_code=r.statement_currency
            ) direction_rows
            -- Zero amounts have no direction; coverage stays subject to V271.
            WHERE direction<>0
            GROUP BY order_ref,direction
            HAVING count(report_amount)=0 OR count(finance_amount)=0
              OR sum(report_amount) IS DISTINCT FROM sum(finance_amount)
          ) AS direction_bucket_conflict,
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
                    foreignLines,multiRefs,sharedReports,rs.getBoolean("order_bucket_conflict"),
                    rs.getBoolean("leaf_coverage_conflict"),rs.getBoolean("direction_bucket_conflict")),false,false);
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
        return decision(declared,detail,finances,count,otherCurrency,expectedLines,actualLines,
            foreignLines,multiSettlementEvents,sharedReports,false);
    }

    static String decision(BigDecimal declared,BigDecimal detail,
        BigDecimal finances,long count,long otherCurrency,
        long expectedLines,long actualLines,long foreignLines,
        long multiSettlementEvents,long sharedReports,boolean orderBucketConflict) {
        return decision(declared,detail,finances,count,otherCurrency,expectedLines,actualLines,
            foreignLines,multiSettlementEvents,sharedReports,orderBucketConflict,false,false);
    }

    static String decision(BigDecimal declared,BigDecimal detail,
        BigDecimal finances,long count,long otherCurrency,
        long expectedLines,long actualLines,long foreignLines,
        long multiSettlementEvents,long sharedReports,boolean orderBucketConflict,
        boolean leafCoverageConflict,boolean directionBucketConflict) {
        if(expectedLines<1||actualLines!=expectedLines)
            return "REPORT_LINE_COUNT_MISMATCH";
        if(foreignLines>0)return "REPORT_LINE_CURRENCY_CONFLICT";
        if(multiSettlementEvents>0)
            return "FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM";
        if(sharedReports>0)
            return "SETTLEMENT_REFERENCE_SHARED_REPORTS";
        if(detail==null)return "REPORT_DETAIL_INCOMPLETE";
        // V54 deliberately permits null declared_net. An absent statement
        // total is UNKNOWN, not an affirmative three-way source agreement.
        if(declared==null)return "REPORT_DECLARED_NET_UNAVAILABLE";
        if(declared.compareTo(detail)!=0)
            return "REPORT_INTERNAL_DIFFERENCE";
        if(otherCurrency>0)return "CURRENCY_CONFLICT";
        if(count==0 || finances==null)return "NO_FINANCES_SETTLEMENT_REFERENCE";
        if(detail.compareTo(finances)!=0)return "SOURCE_TOTAL_DIFFERENCE";
        if(orderBucketConflict)return "ORDER_REFERENCE_AMOUNT_DIFFERENCE";
        if(leafCoverageConflict)return "FINANCES_LEAF_COVERAGE_INCOMPLETE";
        if(directionBucketConflict)return "ORDER_DIRECTION_AMOUNT_DIFFERENCE";
        return "SOURCE_AMOUNTS_AGREE_UNVERIFIED";
    }
    private static String money(BigDecimal value) {
        return value==null?null:value.toPlainString();
    }
}
