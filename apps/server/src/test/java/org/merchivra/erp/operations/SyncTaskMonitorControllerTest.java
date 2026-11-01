package org.merchivra.erp.operations;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncTaskMonitorControllerTest {
    @Test
    void getsTenantOnlyFromOwnerSessionAndRespondsNoStore() {
        TenantAccessService access=mock(TenantAccessService.class);
        SyncTaskMonitorService service=mock(SyncTaskMonitorService.class);
        HttpServletRequest request=mock(HttpServletRequest.class);
        Authentication auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var summary=new SyncTaskMonitorService.Summary(0,0,0,0,0,0);
        var snapshot=new SyncTaskMonitorService.Snapshot(summary,List.of(),50,false);
        when(service.snapshot(tenant,"ALL","ALL",50)).thenReturn(snapshot);
        var controller=new SyncTaskMonitorController(access,service);

        var response=controller.snapshot(auth,request,"ALL","ALL",50);

        assertSame(snapshot,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).snapshot(tenant,"ALL","ALL",50);
        verifyNoMoreInteractions(service);
    }
}
