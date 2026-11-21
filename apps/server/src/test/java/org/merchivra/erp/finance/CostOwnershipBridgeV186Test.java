package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostOwnershipBridgeV186Test {
    private static final UUID MP=UUID.randomUUID();
    private static LocalDate date(String value) {return LocalDate.parse(value);}
    private static CostOwnershipBridgeServiceV186.Aggregate g(
        String dim,long sources,long approved,long pending,long unassigned,
        long missingAudit,long bad,String claimed,String allocated,String remaining) {
        return new CostOwnershipBridgeServiceV186.Aggregate(
            "INBOUND_FREIGHT","USD",dim,sources,approved,pending,unassigned,
            missingAudit,bad,new BigDecimal(claimed),new BigDecimal(allocated),
            new BigDecimal(remaining));
    }
    private static CostOwnershipBridgeServiceV186.Report report(
        List<CostOwnershipBridgeServiceV186.Aggregate> groups) {
        return CostOwnershipBridgeServiceV186.summarize(
            MP,date("2026-11-01"),date("2026-12-01"),groups);
    }
    private static void blocked(CostOwnershipBridgeServiceV186.Report r) {
        assertFalse(r.doubleCountingAcrossDimensionsAllowed());
        assertFalse(r.externalChargeVerified());
        assertFalse(r.refundCogsReversalVerified());
        assertFalse(r.fxConversionApproved());
        assertFalse(r.bankReconciled());
        assertFalse(r.ledgerPosted());
        assertFalse(r.netProfitEligible());
        assertNull(r.realizedNetProfit());
        assertTrue(r.blockerCodes().contains(
            "REFUND_COGS_REVERSAL_NEEDS_PHYSICAL_RECEIPT_AND_FINANCIAL_POLICY"));
    }

    @Test void oneSourceOnlyInPrimaryDimensionAndNoSecondaryExpenseDebit() {
        var r=report(List.of(g("SKU",1,1,0,0,0,0,"10","7.25","2.75")));
        assertEquals(1,r.sourceCount());
        assertEquals("V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE",
            r.sourceDateBasis());
        assertEquals(1,r.buckets().size());
        var bucket=r.buckets().getFirst();
        assertEquals("SKU",bucket.primaryDimension());
        assertEquals("10.000000",bucket.sourceClaimedAmount());
        assertEquals("7.250000",bucket.approvedUnverifiedAllocatedAmount());
        assertEquals("2.750000",bucket.approvedUnverifiedRemainderAmount());
        assertFalse(bucket.derivativeSkuOrderPeriodProfitDeductionsAllowed());
        assertFalse(bucket.netProfitEligible());
        assertEquals(7,r.categoriesWithoutSourceClaims().size());
        blocked(r);
    }

    @Test void skuOrderPeriodReferenceViewsCannotTripleCountSource() {
        var r=report(List.of(
            g("SKU",1,1,0,0,0,0,"10","6.5","3.5"),
            g("ORDER",2,1,1,0,0,0,"30","10","0"),
            g("PERIOD",1,1,0,0,0,0,"5","5","0"),
            g("UNASSIGNED",3,0,0,3,0,0,"7","0","0")));
        assertEquals(7,r.sourceCount());
        assertEquals(List.of("SKU","ORDER","PERIOD","UNASSIGNED"),
            r.buckets().stream().map(
                CostOwnershipBridgeServiceV186.Bucket::primaryDimension).toList());
        assertNull(r.buckets().get(3).approvedUnverifiedAllocatedAmount());
        assertNull(r.buckets().get(3).approvedUnverifiedRemainderAmount());
        assertTrue(r.blockerCodes().contains("SOURCE_WITHOUT_ACTIVE_V92_OWNER_DIMENSION"));
        assertTrue(r.blockerCodes().contains("PENDING_V92_ALLOCATION_REVIEW"));
        blocked(r);
    }

    @Test void emptyMarketplaceMonthIsUnknownNotZeroCost() {
        var r=report(List.of());
        assertEquals(0,r.sourceCount());
        assertTrue(r.buckets().isEmpty());
        assertEquals(8,r.categoriesWithoutSourceClaims().size());
        assertTrue(r.blockerCodes().contains(
            "COST_CATEGORIES_WITHOUT_SOURCE_CLAIMS_UNKNOWN_NOT_ZERO"));
        blocked(r);
    }

    @Test void auditMissingIsNotIndependentEvidenceOfExternalCost() {
        var r=report(List.of(g("PERIOD",1,1,0,0,1,0,
            "10.000000","7.000000","3.000000")));
        assertEquals(1,r.buckets().getFirst().approvedMissingAuditSourceCount());
        assertTrue(r.blockerCodes().contains(
            "INDEPENDENT_V92_APPROVAL_AUDIT_MISSING"));
        blocked(r);
    }

    @Test void failClosedOnOverlapsBadAmountsMissingOrContradictoryDimensions() {
        var bad=List.of(
            g("SKU",1,2,0,0,0,0,"10","9","1"),
            g("SKU",1,1,0,0,0,1,"10","9","1"),
            g("UNASSIGNED",1,1,0,0,0,0,"10","9","1"),
            g("PERIOD",1,0,0,1,0,0,"10","0","0"),
            g("SKU",1,1,0,0,0,0,"10","10.000001","0"),
            g("SKU",1,1,0,0,0,0,"10","8","3"),
            g("SKU",1,0,1,0,0,0,"10","1","0"));
        for(var item:bad)
            assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class,
                    ()->report(List.of(item))).getStatusCode(),item.toString());
        var a=g("ORDER",1,1,0,0,0,0,"10","10","0");
        assertThrows(ResponseStatusException.class,()->report(List.of(a,a)));
        assertThrows(ResponseStatusException.class,()->report(List.of(
            new CostOwnershipBridgeServiceV186.Aggregate(
                "NOT_COST","USD","SKU",1,1,0,0,0,0,
                BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO))));
    }

    @Test void onlyHalfOpenBoundedPeriodAndActiveMarketplaceAreAllowed() {
        for(var dates:List.of(
            List.of(date("2026-11-01"),date("2026-11-01")),
            List.of(date("2026-11-01"),date("2026-10-01")),
            List.of(date("2025-01-01"),date("2026-11-01")))) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    CostOwnershipBridgeServiceV186.validate(
                        dates.get(0),dates.get(1))).getStatusCode());
        }
        var jdbc=mock(JdbcTemplate.class);
        var svc=new CostOwnershipBridgeServiceV186(jdbc);
        UUID tenant=UUID.randomUUID();
        var missing=assertThrows(ResponseStatusException.class,
            ()->svc.report(tenant,MP,date("2026-11-01"),date("2026-12-01")));
        assertEquals(HttpStatus.NOT_FOUND,missing.getStatusCode());
        verify(jdbc,times(1)).query(contains("FROM seller_marketplace m"),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(tenant),eq(MP));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void existingOwnerSessionControlsScopeAndNeverCaches() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostOwnershipBridgeServiceV186.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var start=date("2026-11-01");var end=date("2026-12-01");
        var response=new CostOwnershipBridgeControllerV186(access,service)
            .report(auth,req,MP,start,end);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).report(tenant,MP,start,end);
    }

    @Test void readOnlySqlUsesOneFactRowPerV91SourceNotOneRowPerLine()
        throws Exception {
        var code=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostOwnershipBridgeServiceV186.java"));
        assertTrue(code.contains("isolation=Isolation.REPEATABLE_READ"));
        assertTrue(code.contains("max(b.id::text)::uuid"));
        assertTrue(code.contains("active.active_count"));
        assertTrue(code.contains("count(*) FILTER(WHERE active_count>1"));
        assertTrue(code.contains("GROUP BY category,currency,coalesce(target_kind,'UNASSIGNED')"));
        assertTrue(code.contains("AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')"));
        assertTrue(code.contains("AND s.business_date>=? AND s.business_date<?"));
        assertTrue(code.contains("a.action='APPROVED_UNVERIFIED'"));
        assertFalse(code.contains("INSERT INTO finance_"));
        assertFalse(code.contains("UPDATE finance_"));
        assertFalse(code.contains("MERCHIVRA_SPAPI_ENABLED=true"));
    }
}
