package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
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

class CostIdentityReconciliationServiceTest {
    @Test void noLocalMatchesNeverBecomeTrustedOrCompleteSources() {
        var id=UUID.randomUUID();
        var x=CostIdentityReconciliationService.summarize(id,
            "REVIEWED_UNVERIFIED",Collections.nCopies(8,false));
        assertEquals("NO_LOCAL_IDENTITY_MATCH_UNVERIFIED",x.status());
        assertEquals(id.toString(),x.sourceId());
        assertEquals(8,x.signals().size());
        assertTrue(x.signals().stream().noneMatch(v->v.exactLocalIdentityOverlap()));
        assertFalse(x.approvalHeld());
        assertFalse(x.independentExternalEvidenceVerified());
        assertFalse(x.completeSourceCoverage());
        assertFalse(x.netProfitVerified());
    }
    @Test void everyIdentityFamilyIndividuallyTriggersConservativeHold() {
        var id=UUID.randomUUID();
        for(int i=0;i<8;i++) {
            var bits=new ArrayList<>(Collections.nCopies(8,false));
            bits.set(i,true);
            var p=CostIdentityReconciliationService.summarize(id,"REVIEWED_UNVERIFIED",bits);
            assertTrue(p.approvalHeld(),Integer.toString(i));
            assertEquals("POTENTIAL_IDENTITY_COLLISION_HOLD",p.status());
            assertEquals(1,p.signals().stream().filter(x->x.exactLocalIdentityOverlap()).count());
            assertFalse(p.netProfitVerified());
            assertFalse(p.completeSourceCoverage());
        }
        assertThrows(IllegalArgumentException.class,()->
            CostIdentityReconciliationService.summarize(id,"STAGED",List.of(true)));
    }
    @Test void ownerSessionScopeAndCacheControlNotProvidedByBrowser() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostIdentityReconciliationService.class);
        var authentication=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),source=UUID.randomUUID();
        when(access.currentTenant(authentication,request)).thenReturn(tenant);
        var controller=new CostIdentityReconciliationController(access,service);
        var resp=controller.preview(authentication,request,mp,source);
        assertTrue(resp.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,mp,source);
    }
    @Test void holdsDoNotMutateSourceAndNeverReadRemoteAmazon() {
        var jdbc=mock(JdbcTemplate.class);
        var scan=spy(new CostIdentityReconciliationService(jdbc));
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),source=UUID.randomUUID();
        doReturn(CostIdentityReconciliationService.summarize(source,
            "REVIEWED_UNVERIFIED",List.of(false,true,false,false,false,false,false,false)))
            .when(scan).preview(tenant,mp,source);
        var e=assertThrows(ResponseStatusException.class,()->
            scan.requireNoPotentialOverlap(tenant,mp,source));
        assertEquals(HttpStatus.CONFLICT,e.getStatusCode());
        verifyNoInteractions(jdbc);
        doReturn(CostIdentityReconciliationService.summarize(source,
            "REVIEWED_UNVERIFIED",Collections.nCopies(8,false)))
            .when(scan).preview(tenant,mp,source);
        scan.requireNoPotentialOverlap(tenant,mp,source);
        verifyNoInteractions(jdbc);
    }
    @Test void scannerOnlyConsultsTenantMarketplaceAndAvoidsUntrustedMoneyComparison() throws Exception {
        var sql=java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/org/merchivra/erp/finance/CostIdentityReconciliationService.java"));
        for(var table:List.of("finance_cost_source_v91","finance_ad_spend_reference",
            "ads_report_import","finance_source_event","spapi_settlement_report",
            "finance_settlement_statement"))assertTrue(sql.contains(table),table);
        for(var predicate:List.of("a.seller_marketplace_id=s.seller_marketplace_id",
            "f.seller_marketplace_id=s.seller_marketplace_id",
            "r.seller_marketplace_id=s.seller_marketplace_id",
            "c.id<>s.id","source_event_key=s.source_key"))
            assertTrue(sql.contains(predicate),predicate);
        assertFalse(sql.contains("spend_amount=s.amount"));
        assertFalse(sql.contains("f.amount=s.amount"));
        assertFalse(sql.contains("s.amount=f.amount"));
    }
}
