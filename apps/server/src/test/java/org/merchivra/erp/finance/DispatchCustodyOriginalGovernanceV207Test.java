package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchCustodyOriginalGovernanceV207Test {
    private static UUID id(int x) {
        return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012x",x));
    }

    @Test void retentionNeverAllowsDeletionOrPretendsIssuerVerified() {
        var svc=new DispatchCustodyOriginalGovernanceServiceV207(mock(JdbcTemplate.class));
        assertEquals("OPERATOR_REGISTRY",
            DispatchCustodyOriginalGovernanceServiceV207.kind("OPERATOR_REGISTRY"));
        assertEquals("CARRIER_HANDOFF",
            DispatchCustodyOriginalGovernanceServiceV207.kind("CARRIER_HANDOFF"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyOriginalGovernanceServiceV207.kind("ANY")).getStatusCode());
        assertEquals("REVIEWED_NO_PURGE",
            DispatchCustodyOriginalGovernanceServiceV207.decision("REVIEWED_NO_PURGE"));
        assertEquals("REJECTED",
            DispatchCustodyOriginalGovernanceServiceV207.decision("REJECTED"));
        for(var requested:new String[]{"APPROVE_DELETE","RELEASE_HOLD","ISSUER_VERIFIED",
                "EXTERNAL_FULFILLMENT_CONFIRMED","COGS_POSTED"}) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->DispatchCustodyOriginalGovernanceServiceV207.decision(requested))
                .getStatusCode());
        }
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyOriginalGovernanceServiceV207.reason("too short"));
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyOriginalGovernanceServiceV207.reason(
                "valid reason\nforged"));
        assertEquals("No automatic deletion or release",
            DispatchCustodyOriginalGovernanceServiceV207.reason(
                "No automatic deletion or release"));
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyOriginalGovernanceServiceV207.retentionDate(
                OffsetDateTime.now().minusDays(1).toString()));
        assertThrows(ResponseStatusException.class,
            ()->DispatchCustodyOriginalGovernanceServiceV207.retentionDate(
                OffsetDateTime.now().plusYears(101).toString()));
        assertNotNull(DispatchCustodyOriginalGovernanceServiceV207.retentionDate(
            OffsetDateTime.now().plusDays(90).toString()));
    }

    @Test void unknownScopedOriginalMustNotBeDisclosedOrAudited() {
        var jdbc=mock(JdbcTemplate.class);
        var svc=new DispatchCustodyOriginalGovernanceServiceV207(jdbc);
        var missing=assertThrows(ResponseStatusException.class,
            ()->svc.viewAndAudit(id(1),id(2),id(3),id(4),
                "OPERATOR_REGISTRY",id(5)));
        assertEquals(HttpStatus.NOT_FOUND,missing.getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->svc.viewAndAudit(id(1),id(2),id(3),id(4),
                "CARRIER_HANDOFF",null)).getStatusCode());
    }

    @Test void ownerTenantScopedRoutesUseNoStoreAndNoBinaryDisclosures() {
        var access=mock(TenantAccessService.class);
        var service=mock(DispatchCustodyOriginalGovernanceServiceV207.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=id(1),mp=id(2),order=id(3),dossier=id(4),actor=id(5),proposal=id(6);
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var ctl=new DispatchCustodyOriginalGovernanceControllerV207(access,service);
        assertTrue(ctl.capacity(auth,req).getHeaders().getCacheControl().contains("no-store"));
        verify(service).capacity(tenant);
        assertTrue(ctl.metadata(auth,req,mp,order,dossier,"CARRIER_HANDOFF")
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).viewAndAudit(tenant,mp,order,dossier,"CARRIER_HANDOFF",actor);
        var candidate=new DispatchCustodyOriginalGovernanceServiceV207.RetentionInput(
            OffsetDateTime.now().plusYears(5).toString(),
            "Legal archival retention proposal");
        assertTrue(ctl.propose(auth,req,mp,order,dossier,"OPERATOR_REGISTRY",candidate)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).propose(tenant,mp,order,dossier,"OPERATOR_REGISTRY",actor,candidate);
        var choice=new DispatchCustodyOriginalGovernanceServiceV207.DecisionInput(
            "REVIEWED_NO_PURGE","Independent review leaves permanent hold");
        assertTrue(ctl.review(auth,req,mp,order,dossier,"OPERATOR_REGISTRY",proposal,choice)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).review(tenant,mp,order,dossier,
            "OPERATOR_REGISTRY",proposal,actor,choice);
    }
}
