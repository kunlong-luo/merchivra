package org.merchivra.erp.finance;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkuProfitPreviewServiceTest {
    private static final Clock FIXED=Clock.fixed(
        Instant.parse("2026-11-01T12:00:00Z"),ZoneOffset.UTC);
    @Test
    void rejectsBadDateAndUnsafeOffsetWithoutQueryingSources() {
        var date=LocalDate.of(2026,10,1);
        SkuProfitPreviewService.validate(date,date.plusDays(31),0,FIXED);
        for(int offset:new int[]{-1,1,3001})
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    SkuProfitPreviewService.validate(date,date.plusDays(30),
                        offset,FIXED)).getStatusCode());
        assertThrows(ResponseStatusException.class,()->
            SkuProfitPreviewService.validate(date,date.plusDays(93),0,FIXED));
        assertThrows(ResponseStatusException.class,()->
            SkuProfitPreviewService.validate(date,date,0,FIXED));
        assertThrows(ResponseStatusException.class,()->
            SkuProfitPreviewService.validate(date,date.plusMonths(2).plusDays(1),
                0,FIXED));
    }
    @Test
    void ownerOnlyEndpointDerivesTenantFromSessionAndNoStore() {
        var access=mock(TenantAccessService.class);
        var service=mock(SkuProfitPreviewService.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        var tenant=UUID.randomUUID();
        var mp=UUID.randomUUID();
        var from=LocalDate.of(2026,10,1);
        var end=LocalDate.of(2026,11,1);
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var controller=new SkuProfitPreviewController(access,service);
        var response=controller.preview(auth,req,mp,from,end,0);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,mp,from,end,0);
    }
}
