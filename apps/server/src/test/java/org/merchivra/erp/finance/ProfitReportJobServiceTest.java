package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfitReportJobServiceTest {
    private static final Clock FIXED=Clock.fixed(
        Instant.parse("2026-11-01T12:00:00Z"),ZoneOffset.UTC);
    @Test void rejectsInvalidDateWindowsAndUnboundedFuturePeriodsBeforeDatabaseAccess() {
        LocalDate after=LocalDate.of(2026,9,1);
        ProfitReportJobService.validate(after,after.plusDays(92),FIXED);
        assertThrows(ResponseStatusException.class,()->
            ProfitReportJobService.validate(after,after.plusDays(93),FIXED));
        assertThrows(ResponseStatusException.class,()->
            ProfitReportJobService.validate(after,after,FIXED));
        assertThrows(ResponseStatusException.class,()->
            ProfitReportJobService.validate(after,LocalDate.of(2026,11,4),FIXED));
        var jdbc=mock(JdbcTemplate.class);
        var service=new ProfitReportJobService(jdbc,
            mock(OrderCostReadinessService.class),
            mock(OrderContributionService.class),
            mock(MultiSkuContributionService.class),
            mock(PlatformTransactionManager.class),FIXED);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                service.create(tenant,mp,UUID.randomUUID(),UUID.randomUUID(),
                    after,after)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void endpointsTakeTenantFromOwnerSessionAndDoNotEnableCache() {
        var access=mock(TenantAccessService.class);
        var service=mock(ProfitReportJobService.class);
        var csv=mock(ProfitReportCsv.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),job=UUID.randomUUID(),
            actor=UUID.randomUUID(),requestKey=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new ProfitReportJobController(access,service,csv);
        var from=LocalDate.of(2026,9,1);
        var before=LocalDate.of(2026,10,1);
        assertTrue(api.recent(auth,req,mp).getHeaders().getCacheControl()
            .contains("no-store"));
        api.create(auth,req,mp,new ProfitReportJobController.Create(
            requestKey,from,before));
        verify(service).create(tenant,mp,actor,requestKey,from,before);
        api.step(auth,req,mp,job);
        verify(service).step(tenant,mp,job);
        api.resume(auth,req,mp,job);
        verify(service).resume(tenant,mp,job);
        assertTrue(api.report(auth,req,mp,job,0).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).report(tenant,mp,job,0);
        verifyNoInteractions(csv);
    }
    @Test void exportRejectsUnfinishedJobsAndNeverQueriesLiveFinances(){
        var service=mock(ProfitReportJobService.class);
        var csv=new ProfitReportCsv(service);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),id=UUID.randomUUID();
        var incomplete=new ProfitReportJobService.Job(id.toString(),"READY",
            "2026-09-01","2026-10-01","USD",10,1,1,0,0,null,
            "0",0,"2026-11-01T00:00:00Z",null,true,false,false,false,false);
        when(service.report(tenant,mp,id,0)).thenReturn(
            new ProfitReportJobService.Report(incomplete,
                new ProfitReportJobService.Totals("USD",1,0,1,
                    "10","-1","-1","4","4"),List.of(),
                List.of(),0,false));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->
                csv.export(tenant,mp,id)).getStatusCode());
        verify(service).report(tenant,mp,id,0);
    }
    @Test void csvEscapesFormulaLikeSkuAndPreservesBlockedNullAmounts() {
        var service=mock(ProfitReportJobService.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),id=UUID.randomUUID();
        var job=new ProfitReportJobService.Job(id.toString(),"SUCCEEDED",
            "2026-09-01","2026-10-01","USD",2,2,1,1,0,null,
            "5.50",1,"2026-11-01T00:00:00Z","2026-11-01T00:10:00Z",
            true,false,false,false,false);
        var totals=new ProfitReportJobService.Totals("USD",1,1,2,
            "100","-10","-5","30","55");
        var skus=List.of(new ProfitReportJobService.Sku(
            "=HYPERLINK(1)",1,1,"USD","100","-10","-5","30","55"));
        var orders=List.of(
            new ProfitReportJobService.Order(UUID.randomUUID().toString(),
                0,"ELIGIBLE","ELIGIBLE_PREVIEW","USD",
                "100","-10","-5","30","55","2026-11-01T00:00:00Z","a".repeat(64)),
            new ProfitReportJobService.Order(UUID.randomUUID().toString(),
                1,"BLOCKED","REFUND_COST_REVERSAL_UNVERIFIED","USD",
                null,null,null,null,null,"2026-11-01T00:00:00Z","b".repeat(64)));
        when(service.report(tenant,mp,id,0)).thenReturn(
            new ProfitReportJobService.Report(job,totals,skus,orders,0,false));
        String output=new ProfitReportCsv(service).export(tenant,mp,id);
        assertTrue(output.startsWith("\uFEFF\"kind\""));
        assertTrue(output.contains("\"'=HYPERLINK(1)\""));
        assertTrue(output.contains("REFUND_COST_REVERSAL_UNVERIFIED"));
        assertTrue(output.contains("\"false\",\"false\",\"false\""));
        assertFalse(output.contains("5.50")); // manual Ads never silently deducted
        assertEquals(4,output.split("\n").length);
    }
}
