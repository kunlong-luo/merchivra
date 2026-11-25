package org.merchivra.erp.finance;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.merchivra.erp.finance.SettlementCategoryPreviewV273.*;

class SettlementCategoryMappingV274Test {
    private static final Mapping A=new Mapping(new ReportType("Order","ItemFees","Commission"),"AMAZON_FEE");
    private static final Mapping B=new Mapping(new ReportType("Refund",null,"Principal"),"PRODUCT_REFUND");
    @Test void orderIndependentContentAddressAndExactNullRoundTrip() {
        var first=SettlementCategoryMappingV274.canonical(List.of(A,B));
        assertEquals(first,SettlementCategoryMappingV274.canonical(List.of(B,A)));
        assertEquals(first,SettlementCategoryMappingV274.stored(first.payload(),first.sha256()));
        assertEquals(64,first.sha256().length());
        assertTrue(first.mappings().contains(B));
        var changed=SettlementCategoryMappingV274.canonical(List.of(
            new Mapping(A.reportType(),"FBA_FEE"),B));
        assertNotEquals(first.sha256(),changed.sha256());
        assertThrows(IllegalArgumentException.class,()->SettlementCategoryMappingV274.stored(
            changed.payload(),first.sha256()));
    }
    @Test void rejectsInvalidAndAmbiguousMappings() {
        assertThrows(IllegalArgumentException.class,()->SettlementCategoryMappingV274.canonical(List.of(A,A)));
        assertThrows(IllegalArgumentException.class,()->SettlementCategoryMappingV274.canonical(List.of(
            new Mapping(new ReportType("Order","~","Commission"),"AMAZON_FEE"))));
        assertThrows(IllegalArgumentException.class,()->SettlementCategoryMappingV274.canonical(List.of(
            new Mapping(new ReportType("Order","a|b",null),"AMAZON_FEE"))));
        assertThrows(IllegalArgumentException.class,()->SettlementCategoryMappingV274.canonical(List.of(
            new Mapping(A.reportType(),"UNCLASSIFIED"))));
    }
    @Test void evidenceBindsScopeMappingSourceAndRuleRevisionDigest() {
        var tenant=java.util.UUID.randomUUID();
        var market=java.util.UUID.randomUUID();
        var report=java.util.UUID.randomUUID();
        String a="a".repeat(64),b="b".repeat(64),c="c".repeat(64),d="d".repeat(64);
        String digest=SettlementCategoryMappingV274.evidence(tenant,market,report,a,b,c);
        assertEquals(digest,SettlementCategoryMappingV274.evidence(tenant,market,report,a,b,c));
        assertNotEquals(digest,SettlementCategoryMappingV274.evidence(tenant,market,report,d,b,c));
        assertNotEquals(digest,SettlementCategoryMappingV274.evidence(tenant,market,report,a,d,c));
        assertNotEquals(digest,SettlementCategoryMappingV274.evidence(tenant,market,report,a,b,d));
        assertNotEquals(digest,SettlementCategoryMappingV274.evidence(tenant,market,java.util.UUID.randomUUID(),a,b,c));
        assertThrows(IllegalArgumentException.class,()->SettlementCategoryMappingV274.evidence(tenant,market,report,a,b,null));
    }
    @Test void persistenceQueriesKeepReportScopeAndNeverUpdate() {
        var save=SettlementReportComparisonService.SAVE_MAPPING_SQL;
        var read=SettlementReportComparisonService.READ_MAPPING_SQL;
        for(var query:List.of(save,read)) {
            assertTrue(query.contains("r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?"));
            assertTrue(query.contains("t.status='ACTIVE'"));
            assertFalse(query.contains("UPDATE"));
        }
        assertTrue(save.contains("ON CONFLICT (tenant_id,report_id,mapping_sha256) DO NOTHING"));
    }
}
