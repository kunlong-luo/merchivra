package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
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

/** Pure six-decimal V185 projection + existing Owner-scope API regression. */
class PeriodCostAccrualPreviewV185Test {
    private static final UUID SOURCE=UUID.randomUUID(), PLAN=UUID.randomUUID();
    private static LocalDate d(String s) {return LocalDate.parse(s);}
    private static CostAllocationEngine.Line period(String from,String to,String amount) {
        return new CostAllocationEngine.Line(null,null,d(from),d(to),amount);
    }
    private static PeriodCostAccrualPreviewV185.Preview preview(String source,
        String state,List<CostAllocationEngine.Line> lines) {
        return PeriodCostAccrualPreviewV185.calculate(SOURCE,PLAN,state,source,"USD",lines);
    }
    private static void neverRecognize(PeriodCostAccrualPreviewV185.Preview result) {
        assertEquals(PeriodCostAccrualPreviewV185.RULE,result.ruleVersion());
        assertFalse(result.externalSourceVerified());
        assertFalse(result.accountingPolicyApproved());
        assertFalse(result.currencyConverted());
        assertFalse(result.refundCogsReversalVerified());
        assertFalse(result.ledgerPosted());
        assertFalse(result.netProfitEligible());
        assertNull(result.realizedNetProfit());
        assertTrue(result.blockerCodes().contains("G1_DAILY_PERIODIZATION_POLICY_NOT_APPROVED"));
        assertTrue(result.blockerCodes().contains("DEC_08_FX_CONVERSION_POLICY_NOT_APPROVED"));
    }

    @Test void crossMonthFourDaysPreservesEveryMicroUnitAndUnallocatedRemainder() {
        var x=preview("12.000001","APPROVED_UNVERIFIED",List.of(
            period("2026-11-29","2026-12-03","10.000001")));
        assertEquals("12.000001",x.sourceAmount());
        assertEquals("10.000001",x.allocatedAmount());
        assertEquals("2.000000",x.unallocatedAmount());
        assertEquals("10.000001",x.projectedAmount());
        assertEquals(List.of("2026-11","2026-12"),
            x.months().stream().map(PeriodCostAccrualPreviewV185.Month::period).toList());
        assertEquals(List.of("5.000001","5.000000"),
            x.months().stream().map(PeriodCostAccrualPreviewV185.Month::projectedAmount).toList());
        assertFalse(x.overlappingServiceDays());
        assertTrue(x.blockerCodes().contains("ORIGINAL_COST_REMAINDER_UNALLOCATED"));
        assertFalse(x.blockerCodes().contains("INDEPENDENT_V92_ALLOCATION_APPROVAL_NOT_COMPLETE"));
        neverRecognize(x);
    }

    @Test void leastMicroUnitBelongsToEarliestDateNotFloatingPointRounding() {
        var x=preview("0.000001","PENDING_REVIEW",
            List.of(period("2026-11-30","2026-12-03","0.000001")));
        assertEquals(List.of("0.000001","0.000000"),
            x.months().stream().map(PeriodCostAccrualPreviewV185.Month::projectedAmount).toList());
        assertEquals("0.000000",x.unallocatedAmount());
        assertTrue(x.blockerCodes().contains("INDEPENDENT_V92_ALLOCATION_APPROVAL_NOT_COMPLETE"));
        neverRecognize(x);
    }

    @Test void leapYearHalfOpenDatesAndCalendarMonthBoundariesAreCorrect() {
        var x=preview("1","APPROVED_UNVERIFIED",
            List.of(period("2028-02-28","2028-03-02","1")));
        assertEquals(List.of("0.666667","0.333333"),
            x.months().stream().map(PeriodCostAccrualPreviewV185.Month::projectedAmount).toList());
        assertEquals("1.000000",x.projectedAmount());
        neverRecognize(x);
    }

    @Test void sourceCanIncludeSeveralRangesButOverlapIsNeverSilentlyApproved() {
        var a=period("2026-11-28","2026-12-02","2.00");
        var b=period("2026-11-30","2026-12-04","3.00");
        var p=preview("5.00","APPROVED_UNVERIFIED",List.of(a,b));
        var reversed=preview("5.000000","APPROVED_UNVERIFIED",List.of(b,a));
        assertEquals(p,reversed);
        assertEquals(List.of("2.000000","3.000000"),
            p.months().stream().map(PeriodCostAccrualPreviewV185.Month::projectedAmount).toList());
        assertTrue(p.overlappingServiceDays());
        assertTrue(p.blockerCodes().contains("OVERLAPPING_SERVICE_DAYS_REQUIRE_POLICY_REVIEW"));
        neverRecognize(p);
    }

    @Test void sameCurrencyCostConservesEvenForDisjointTwoMonthLines() {
        var x=preview("12.340000","REJECTED",List.of(
            period("2026-11-01","2026-11-11","3.14"),
            period("2026-12-01","2026-12-11","9.20")));
        assertEquals("12.340000",x.projectedAmount());
        assertEquals("0.000000",x.unallocatedAmount());
        assertEquals(List.of("3.140000","9.200000"),
            x.months().stream().map(PeriodCostAccrualPreviewV185.Month::projectedAmount).toList());
        assertTrue(x.blockerCodes().contains("INDEPENDENT_V92_ALLOCATION_APPROVAL_NOT_COMPLETE"));
        neverRecognize(x);
    }

    @Test void invalidAmountsDatesOrTargetDimensionsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->
            preview("1","APPROVED_UNVERIFIED",List.of(
                period("2026-11-01","2026-11-02","1.000001"))));
        assertThrows(IllegalArgumentException.class,()->
            preview("1","APPROVED_UNVERIFIED",List.of(
                period("2026-11-01","2027-02-04","1"))));
        assertThrows(IllegalArgumentException.class,()->
            preview("1","APPROVED_UNVERIFIED",List.of(
                period("2026-11-01","2026-11-02","-1"))));
        assertThrows(IllegalArgumentException.class,()->
            preview("1","APPROVED_UNVERIFIED",List.of(
                new CostAllocationEngine.Line(UUID.randomUUID(),null,null,null,"1"))));
        assertThrows(IllegalArgumentException.class,()->
            preview("1","APPROVED_UNVERIFIED",List.of(
                period("2026-11-01","2026-11-02","0"))));
        assertThrows(IllegalArgumentException.class,()->
            preview("1","APPROVED_UNVERIFIED",List.of()));
        assertThrows(IllegalArgumentException.class,()->
            preview("1","FAKE_VERIFIED",List.of(
                period("2026-11-01","2026-11-02","1"))));
    }

    @Test void newEndpointOnlyUsesSessionTenantAndNoStore() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostAllocationService.class);
        var request=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=new CostAllocationController(access,service)
            .periodization(auth,request,mp,SOURCE,PLAN);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).periodizationPreview(tenant,mp,SOURCE,PLAN);
        verifyNoMoreInteractions(service);
    }

    @Test void nonexistentScopedSourceIs404BeforeAnyBatchRead() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CostAllocationService(jdbc,new CostIdentityReconciliationService(jdbc));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.periodizationPreview(UUID.randomUUID(),UUID.randomUUID(),
                SOURCE,PLAN)).getStatusCode());
        verify(jdbc,times(1)).query(
            contains("finance_cost_source_v91"),
            any(org.springframework.jdbc.core.RowMapper.class),
            any(UUID.class),any(UUID.class),eq(SOURCE));
    }

    @Test void previewHasNoWritesNoExchangeRatesAndNoRefundCogsPosting() throws Exception {
        var engine=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/PeriodCostAccrualPreviewV185.java"));
        var service=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostAllocationService.java"));
        assertTrue(service.contains("isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ"));
        assertTrue(service.contains("existing.targetKind()"));
        assertTrue(service.contains("Only V92 PERIOD allocations"));
        assertFalse(engine.contains("JdbcTemplate"));
        assertFalse(engine.contains("INSERT INTO"));
        assertFalse(engine.contains("UPDATE finance_"));
        assertFalse(engine.contains("SPAPI_OAUTH_ENABLED=true"));
        assertTrue(engine.contains("false,false,false,false,false,false,null"));
    }
}
