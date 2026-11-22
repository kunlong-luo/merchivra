package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tenant scope and no-store response for the separate V194 evidence endpoint. */
class ProfitCaptureVerificationControllerV194Test {
    @Test void onlyUsesAuthenticatedTenantAndExplicitMarketplaceAndJob() {
        var access=mock(TenantAccessService.class);
        var reports=mock(ProfitReportJobService.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),marketplace=UUID.randomUUID(),
            job=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var evidence=new ProfitCaptureVerifierV194.Summary(
            2,0,2,0,0,false,false,
            List.of("V194_HISTORICAL_UNVERSIONED_EXTRACT",
                "V194_PROFIT_ARITHMETIC_BLOCKED"));
        when(reports.captureVerification(tenant,marketplace,job))
            .thenReturn(evidence);
        var response=new ProfitCaptureVerificationControllerV194(access,reports)
            .verify(auth,request,marketplace,job);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertSame(evidence,response.getBody());
        assertFalse(response.getBody().profitBaselineAllowed());
        verify(access).currentTenant(auth,request);
        verify(reports).captureVerification(tenant,marketplace,job);
        verifyNoMoreInteractions(reports);
    }
}
