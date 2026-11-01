package org.merchivra.erp.ads;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Thin but explicit authorization boundary checks for every V66 route. */
class AdsReportControllerTest {
    @Test void allEndpointsUseServerOwnerTenantActorAndNoStore(){
        var access=mock(TenantAccessService.class);
        var service=mock(AdsReportService.class);
        var request=mock(HttpServletRequest.class);
        var authentication=mock(Authentication.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),actor=UUID.randomUUID();
        UUID batch=UUID.randomUUID(),profile=UUID.randomUUID();
        when(access.currentTenant(authentication,request)).thenReturn(tenant);
        when(access.authenticatedUser(authentication)).thenReturn(actor);
        var api=new AdsReportController(access,service);
        var auth=api.authorization(authentication,request,mp);
        assertTrue(auth.getHeaders().getCacheControl().contains("no-store"));
        verify(service).profiles(tenant,mp);
        verify(service).capability();
        assertTrue(api.profiles(authentication,request,mp).getHeaders().getCacheControl().contains("no-store"));
        var p=new AdsReportService.ProfileInput("MOCK-PROFILE-1","MOCK");
        api.register(authentication,request,mp,p);
        verify(service).register(tenant,mp,actor,p);
        api.reports(authentication,request,mp);
        verify(service).reports(tenant,mp);
        var body=new AdsReportService.ImportInput(profile,"MOCK-REPORT-1","MOCK",
            java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-01"),
            "USD",List.of(new AdsReportService.CostInput(
                java.time.LocalDate.parse("2026-10-01"),"MOCK-CAMPAIGN","1.25")));
        api.importReport(authentication,request,mp,body);
        verify(service).importReport(tenant,mp,actor,body);
        api.detail(authentication,request,mp,batch);
        verify(service).report(tenant,mp,batch);
        var action=new AdsReportService.Review("APPROVE","Reviewed offline input");
        api.review(authentication,request,mp,batch,action);
        verify(service).review(tenant,mp,actor,batch,action);
        api.daily(authentication,request,mp);
        verify(service).daily(tenant,mp);
    }
}
