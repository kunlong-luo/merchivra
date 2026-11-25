package org.merchivra.erp.finance;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReportComparisonServiceTest {
    private static BigDecimal amount(String s) {
        return s==null?null:new BigDecimal(s);
    }
    private static String decision(String declared,String report,String finance,
        long matching,long otherCurrency) {
        return SettlementReportComparisonService.decision(amount(declared),
            amount(report),amount(finance),matching,otherCurrency,
            2,2,0,0,0);
    }

    @Test
    void matchingAmountsAreNotBankReconciliationOrExternalVerification() {
        assertEquals("SOURCE_AMOUNTS_AGREE_UNVERIFIED",
            decision("17.50","17.5000","17.50",2,0));
        assertEquals("NO_FINANCES_SETTLEMENT_REFERENCE",
            decision("17.50","17.50",null,0,0));
        assertEquals("SOURCE_TOTAL_DIFFERENCE",
            decision("17.50","17.50","17.00",2,0));
        assertEquals("CURRENCY_CONFLICT",
            decision("17.50","17.50","17.50",1,1));
        assertEquals("REPORT_INTERNAL_DIFFERENCE",
            decision("20.00","17.50","17.50",2,0));
        assertEquals("REPORT_DETAIL_INCOMPLETE",
            decision("17.50",null,"17.50",2,0));
    }

    @Test
    void missingDeclaredNetCannotBeMistakenForThreeWayAgreement() {
        assertEquals("REPORT_DECLARED_NET_UNAVAILABLE",
            decision(null,"17.50","17.50",2,0));
        assertEquals("REPORT_DECLARED_NET_UNAVAILABLE",
            decision(null,"0.000000","0.000000",2,0));
        assertEquals("REPORT_LINE_COUNT_MISMATCH",
            SettlementReportComparisonService.decision(null,
                amount("17.50"),amount("17.50"),2,0,2,1,0,0,0));
        // An explicit zero statement is distinguishable from missing.
        assertEquals("SOURCE_AMOUNTS_AGREE_UNVERIFIED",
            decision("0.000000","0.00","0",2,0));
    }

    private static String evidenceStatus(long expected,long actual,
        long foreignLines,long multipleFinancesReferences,long sharedReports) {
        return SettlementReportComparisonService.decision(amount("17.50"),
            amount("17.50"),amount("17.50"),2,0,expected,actual,
            foreignLines,multipleFinancesReferences,sharedReports);
    }

    @Test
    void immutableReportLineCountMismatchCannotMasqueradeAsEqualAmounts() {
        assertEquals("REPORT_LINE_COUNT_MISMATCH",evidenceStatus(2,1,0,0,0));
        assertEquals("REPORT_LINE_COUNT_MISMATCH",evidenceStatus(2,3,0,0,0));
        assertEquals("REPORT_LINE_COUNT_MISMATCH",evidenceStatus(0,0,0,0,0));
    }

    @Test
    void mixedCurrencyLinesCannotPassEvenIfArithmeticSumsMatch() {
        assertEquals("REPORT_LINE_CURRENCY_CONFLICT",
            evidenceStatus(2,2,1,0,0));
    }

    @Test
    void independentlyMatchingTotalsCannotClearMultiSettlementAttribution() {
        assertEquals("FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM",
            evidenceStatus(2,2,0,1,0));
        assertEquals("SETTLEMENT_REFERENCE_SHARED_REPORTS",
            evidenceStatus(2,2,0,0,1));
        assertEquals("FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM",
            evidenceStatus(2,2,0,2,3));
        assertEquals("SOURCE_AMOUNTS_AGREE_UNVERIFIED",
            evidenceStatus(2,2,0,0,0));
    }

    @Test
    void equalTotalsCannotHideOrderReferenceDifferences() {
        assertEquals("ORDER_REFERENCE_AMOUNT_DIFFERENCE",
            SettlementReportComparisonService.decision(amount("17.50"),amount("17.50"),
                amount("17.50"),2,0,2,2,0,0,0,true));
        assertEquals("SOURCE_TOTAL_DIFFERENCE",
            SettlementReportComparisonService.decision(amount("17.50"),amount("17.50"),
                amount("17.49"),2,0,2,2,0,0,0,true));
        assertEquals("REPORT_LINE_COUNT_MISMATCH",
            SettlementReportComparisonService.decision(amount("17.50"),amount("17.50"),
                amount("17.50"),2,0,2,1,0,0,0,true));
    }

    @Test
    void allEvidenceCountsComeFromTenantMarketplaceBoundedScope() {
        String sql=SettlementReportComparisonService.COMPARISON_SQL;
        assertTrue(sql.contains("r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?"));
        assertTrue(sql.contains("ref.tenant_id=r.tenant_id"));
        assertTrue(sql.contains("f.seller_marketplace_id=r.seller_marketplace_id"));
        assertTrue(sql.contains("ref.reference_kind='SETTLEMENT_ID'"));
        assertTrue(sql.contains("ref.reference_value=r.settlement_reference"));
        assertTrue(sql.contains("f.source_family='SPAPI_FINANCES'"));
        assertTrue(sql.contains("f.data_origin='LIVE'"));
        assertTrue(sql.contains("f.currency_code=r.statement_currency"));
        assertTrue(sql.contains("r.line_count AS expected_line_count"));
        assertTrue(sql.contains("entry.report_id=r.id"));
        assertTrue(sql.contains("entry.currency_code<>r.statement_currency"));
        assertTrue(sql.contains("competing.tenant_id=r.tenant_id"));
        assertTrue(sql.contains("competing.seller_marketplace_id=r.seller_marketplace_id"));
        assertTrue(sql.contains("competing.settlement_reference=r.settlement_reference"));
        assertTrue(sql.contains("other.tenant_id=ref.tenant_id"));
        assertTrue(sql.contains("other.event_id=ref.event_id"));
        assertTrue(sql.contains("other.reference_kind='SETTLEMENT_ID'"));
        assertTrue(sql.contains("other.reference_value<>ref.reference_value"));
        // One SQL statement gives the evidence counts a single PG snapshot.
        assertFalse(sql.contains("UPDATE "));
        assertFalse(sql.contains("INSERT "));
    }
}
