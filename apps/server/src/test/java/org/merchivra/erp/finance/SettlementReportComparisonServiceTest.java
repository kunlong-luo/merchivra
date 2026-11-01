package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementReportComparisonServiceTest {
    private static BigDecimal amount(String s){return s==null?null:new BigDecimal(s);}

    @Test
    void doesNotTreatExactAmountsAsBankPaymentOrFullReconciliation(){
        assertEquals("SOURCE_AMOUNTS_AGREE_UNVERIFIED",
            SettlementReportComparisonService.decision(
                amount("17.50"),amount("17.5000"),amount("17.50"),2,0));
        assertEquals("NO_FINANCES_SETTLEMENT_REFERENCE",
            SettlementReportComparisonService.decision(
                null,amount("17.50"),null,0,0));
        assertEquals("SOURCE_TOTAL_DIFFERENCE",
            SettlementReportComparisonService.decision(
                null,amount("17.50"),amount("17.00"),2,0));
        assertEquals("CURRENCY_CONFLICT",
            SettlementReportComparisonService.decision(
                null,amount("17.50"),amount("17.50"),1,1));
        assertEquals("REPORT_INTERNAL_DIFFERENCE",
            SettlementReportComparisonService.decision(
                amount("20.00"),amount("17.50"),amount("17.50"),2,0));
        assertEquals("REPORT_DETAIL_INCOMPLETE",
            SettlementReportComparisonService.decision(
                null,null,amount("17.50"),2,0));
    }

    @Test
    void exactReferenceComparisonNeverSumsUnrelatedManualOrOtherTenantEvents(){
        String sql=SettlementReportComparisonService.COMPARISON_SQL;
        assertTrue(sql.contains("r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?"));
        assertTrue(sql.contains("ref.tenant_id=r.tenant_id"));
        assertTrue(sql.contains("f.seller_marketplace_id=r.seller_marketplace_id"));
        assertTrue(sql.contains("ref.reference_kind='SETTLEMENT_ID'"));
        assertTrue(sql.contains("ref.reference_value=r.settlement_reference"));
        assertTrue(sql.contains("f.source_family='SPAPI_FINANCES'"));
        assertTrue(sql.contains("f.data_origin='LIVE'"));
        assertTrue(sql.contains("f.currency_code=r.statement_currency"));
    }
}
