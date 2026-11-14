package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

class CostAllocationEngineTest {
    private static CostAllocationEngine.Line sku(UUID id,String amt) {
        return new CostAllocationEngine.Line(id,null,null,null,amt);
    }
    private static CostAllocationEngine.Line order(UUID id,String amt) {
        return new CostAllocationEngine.Line(null,id,null,null,amt);
    }
    private static CostAllocationEngine.Line period(LocalDate after,LocalDate before,
                                                    String amt) {
        return new CostAllocationEngine.Line(null,null,after,before,amt);
    }
    @Test void partialAndCompleteOriginalCurrencyExpensesMustConserveMoney() {
        var a=UUID.randomUUID();var b=UUID.randomUUID();
        var p=CostAllocationEngine.evaluate("12.340000","USD","SKU",
            List.of(sku(a,"3.140000"),sku(b,"2.20")));
        assertEquals("PARTIAL_WITH_REMAINDER",p.state());
        assertEquals("5.340000",p.allocatedAmount());
        assertEquals("7.000000",p.unallocatedAmount());
        assertEquals("12.340000",p.sourceAmount());
        assertEquals(2,p.lineCount());
        assertFalse(p.fullyAllocated());
        assertFalse(p.externalCostVerified());
        assertFalse(p.recognizedProfitAllowed());
        var full=CostAllocationEngine.evaluate("12.340000","USD","SKU",
            List.of(sku(a,"3.14"),sku(b,"9.20")));
        assertTrue(full.fullyAllocated());
        assertEquals("0.000000",full.unallocatedAmount());
        assertEquals("FULLY_ASSIGNED_UNVERIFIED",full.state());
        assertFalse(full.recognizedProfitAllowed());
    }
    @Test void multiOrderAndPeriodTargetsDoNotCreateAdditionalAccountingSources() {
        var o=CostAllocationEngine.evaluate("25","EUR","ORDER",
            List.of(order(UUID.randomUUID(),"5"),order(UUID.randomUUID(),"20")));
        assertTrue(o.fullyAllocated());
        var date=LocalDate.parse("2026-11-01");
        var t=CostAllocationEngine.evaluate("10","USD","PERIOD",
            List.of(period(date,date.plusDays(30),"3.00"),
                period(date.plusDays(30),date.plusDays(60),"7.00")));
        assertEquals("0.000000",t.unallocatedAmount());
        assertEquals("10.000000",t.sourceAmount());
        assertEquals("10.000000",t.allocatedAmount());
        assertEquals("0.000000",o.unallocatedAmount());
        assertFalse(t.externalCostVerified());
    }
    @Test void exactAmountSerializationNeverRoundsOrVariesWithInputScale() {
        UUID x=UUID.randomUUID(),y=UUID.randomUUID();
        var shortScale=CostAllocationEngine.evaluate("10","USD","SKU",
            List.of(sku(x,"3.25"),sku(y,"4.0")));
        var fullScale=CostAllocationEngine.evaluate("10.000000","USD","SKU",
            List.of(sku(x,"3.250000"),sku(y,"4.000000")));
        assertEquals(shortScale,fullScale);
        assertEquals("7.250000",shortScale.allocatedAmount());
        assertEquals("2.750000",shortScale.unallocatedAmount());
        assertEquals("10.000000",shortScale.sourceAmount());
        var six=CostAllocationEngine.evaluate("0.000002","USD","SKU",
            List.of(sku(x,"0.000001")));
        assertEquals("0.000001",six.allocatedAmount());
        assertEquals("0.000001",six.unallocatedAmount());
    }
    @Test void failOnOverAllocationDuplicatesAndMixedDimensions() {
        UUID id=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("3","USD","SKU",
                List.of(sku(id,"2"),sku(UUID.randomUUID(),"2"))));
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("3","USD","SKU",
                List.of(sku(id,"1"),sku(id,"1"))));
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("4","USD","SKU",
                List.of(sku(id,"1"),order(UUID.randomUUID(),"1"))));
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("4","USD","ORDER",
                List.of(sku(id,"1"))));
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("4","USD","BAD",List.of(sku(id,"1"))));
    }
    @Test void rejectMissingOrInvalidPeriodAndIllegalZeroOrNegativeAmount() {
        var date=LocalDate.parse("2026-11-01");
        for(var item:List.of(
            period(date,date,"1"),
            period(date,date.plusDays(93),"1"),
            period(date.plusDays(1),date,"1"),
            period(date,date.plusDays(30),"0"),
            period(date,date.plusDays(30),"-1"),
            period(date,date.plusDays(30),"0.0000001"))) {
            assertThrows(IllegalArgumentException.class,()->
                CostAllocationEngine.evaluate("12","USD","PERIOD",List.of(item)));
        }
        for(var value:List.of("0","-1.00","3.1234567","1e3","1,000","")) {
            assertThrows(IllegalArgumentException.class,()->
                CostAllocationEngine.positive(value));
        }
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("12","usd","SKU",
                List.of(sku(UUID.randomUUID(),"1"))));
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("12","USD","SKU",List.of()));
        var tooMany=new ArrayList<CostAllocationEngine.Line>();
        for(int i=0;i<101;i++)tooMany.add(sku(UUID.randomUUID(),"0.01"));
        assertThrows(IllegalArgumentException.class,()->
            CostAllocationEngine.evaluate("100","USD","SKU",tooMany));
    }
    @Test void separateSourceAndAllocationOwnersMustReview() {
        UUID source=UUID.randomUUID(),proposer=UUID.randomUUID(),checker=UUID.randomUUID();
        CostAllocationService.independent(source,proposer,checker);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                CostAllocationService.independent(source,proposer,source)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                CostAllocationService.independent(source,proposer,proposer)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->
                CostAllocationService.independent(source,proposer,null)).getStatusCode());
    }
    @Test void malformedDraftAndReviewReasonFailBeforeDatabase() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CostAllocationService(jdbc,new CostIdentityReconciliationService(jdbc));
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),source=UUID.randomUUID();
        UUID user=UUID.randomUUID();
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->
                service.propose(tenant,mp,source,null,null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                service.propose(tenant,mp,source,user,
                    new CostAllocationService.Draft(null,"SKU","USD",List.of()))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                service.decide(tenant,mp,source,UUID.randomUUID(),user,true,"bad"))
                .getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void ownerSessionCSRFIsAuthoritativeAndResponsesNeverCache() {
        var access=mock(TenantAccessService.class);
        var s=mock(CostAllocationService.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),src=UUID.randomUUID(),
            batch=UUID.randomUUID(),actor=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var c=new CostAllocationController(access,s);
        var draft=new CostAllocationService.Draft(UUID.randomUUID(),
            "SKU","USD",List.of(sku(UUID.randomUUID(),"2.00")));
        assertTrue(c.propose(auth,req,mp,src,draft).getHeaders().getCacheControl()
            .contains("no-store"));
        verify(s).propose(tenant,mp,src,actor,draft);
        assertTrue(c.list(auth,req,mp,src).getHeaders().getCacheControl()
            .contains("no-store"));
        verify(s).list(tenant,mp,src);
        var decision=new CostAllocationController.Decision(
            true,"Independent cost distribution evidence review");
        assertTrue(c.decide(auth,req,mp,src,batch,decision)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(s).decide(tenant,mp,src,batch,actor,true,decision.reason());
        assertTrue(c.audit(auth,req,mp,src,batch).getHeaders().getCacheControl()
            .contains("no-store"));
        verify(s).history(tenant,mp,src,batch);
        verify(access,times(4)).currentTenant(auth,req);
    }
    @Test void sqlEnforcesUniqueActiveProposalImmutableLinesAndApprovalConservation()
        throws Exception {
        String sql=java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/db/migration/V92__cost_allocation_conservation.sql"));
        for(String token:List.of(
            "UNIQUE NULLS NOT DISTINCT","finance_cost_allocation_active_v92",
            "WHERE state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')",
            "REFERENCES finance_cost_source_v91(tenant_id,id)",
            "REFERENCES product_sku(tenant_id,id)",
            "REFERENCES sales_order(tenant_id,id)",
            "finance_cost_allocation_line_guard_v92",
            "finance_cost_allocation_batch_guard_v92",
            "IF TG_OP <> 'INSERT'",
            "source_state IS DISTINCT FROM 'REVIEWED_UNVERIFIED'",
            "allocated>source_amount",
            "NEW.reviewed_by=source_maker",
            "o.seller_marketplace_id=marketplace",
            "CREATE TABLE finance_cost_allocation_audit_v92")) {
            assertTrue(sql.contains(token),token);
        }
        assertFalse(sql.contains("UPDATE finance_profit_report"));
        assertFalse(sql.contains("SPAPI_ENABLED=true"));
    }
}
