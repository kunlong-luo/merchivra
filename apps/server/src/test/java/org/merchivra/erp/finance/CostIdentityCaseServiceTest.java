package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
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

class CostIdentityCaseServiceTest {
    @Test void reviewerMustDifferFromBothClaimMakerAndCaseOpener() {
        UUID source=UUID.randomUUID(),opener=UUID.randomUUID(),checker=UUID.randomUUID();
        for(var outcome:List.of("ESCALATED_NEEDS_PROOF","POTENTIAL_DUPLICATE",
                                "NOT_SAME_CHARGE_UNVERIFIED"))
            CostIdentityCaseService.independent(opener,source,checker,outcome,
                "Independent manual review, not a finance clearing proof");
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                CostIdentityCaseService.independent(opener,source,opener,
                    "POTENTIAL_DUPLICATE","Self review cannot be accepted")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                CostIdentityCaseService.independent(opener,source,source,
                    "ESCALATED_NEEDS_PROOF","Source maker may not clear risk")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->
                CostIdentityCaseService.independent(opener,source,null,
                    "POTENTIAL_DUPLICATE","Unknown reviewer must be rejected")).getStatusCode());
    }
    @Test void noTrustedOrClearFinancialDecisionIsAccepted() {
        UUID s=UUID.randomUUID(),o=UUID.randomUUID(),r=UUID.randomUUID();
        for(String outcome:List.of("CLEAR","SAME_CHARGE_VERIFIED",
                "NOT_DUPLICATE_VERIFIED","REALIZED_NET_PROFIT","REVIEWED_COST")) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
                ResponseStatusException.class,()->
                CostIdentityCaseService.independent(o,s,r,outcome,
                    "Attempted unsupported financial clearance")).getStatusCode());
        }
        for(String reason:List.of("short",
                "review reason with newline\nand a second line",
                "a".repeat(501))) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
                ResponseStatusException.class,()->
                CostIdentityCaseService.independent(o,s,r,
                    "NOT_SAME_CHARGE_UNVERIFIED",reason)).getStatusCode());
        }
    }
    @Test void unauthorizedCaseMutationFailsBeforeAnySql() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CostIdentityCaseService(jdbc);
        UUID t=UUID.randomUUID(),m=UUID.randomUUID(),s=UUID.randomUUID();
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
            service.scan(t,m,s,null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
            service.decide(t,m,s,UUID.randomUUID(),null,
                "ESCALATED_NEEDS_PROOF","Independent review requires owner")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
            service.decide(t,m,s,UUID.randomUUID(),UUID.randomUUID(),
                "CLEAR","Only a proof verified clearing should not be allowed")).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void allOwnerSessionPathsDeriveTenantAndReturnNoStore() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostIdentityCaseService.class);
        var a=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),
            source=UUID.randomUUID(),caseId=UUID.randomUUID(),actor=UUID.randomUUID();
        when(access.currentTenant(a,req)).thenReturn(tenant);
        when(access.authenticatedUser(a)).thenReturn(actor);
        var controller=new CostIdentityCaseController(access,service);
        assertTrue(controller.scan(a,req,mp,source).getHeaders().getCacheControl()
            .contains("no-store"));
        verify(service).scan(tenant,mp,source,actor);
        assertTrue(controller.list(a,req,mp,source).getHeaders().getCacheControl()
            .contains("no-store"));
        verify(service).list(tenant,mp,source);
        var decision=new CostIdentityCaseController.Decision("POTENTIAL_DUPLICATE",
            "Independent review: matching operator-claimed document");
        assertTrue(controller.review(a,req,mp,source,caseId,decision)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).decide(tenant,mp,source,caseId,actor,
            decision.disposition(),decision.reason());
        assertTrue(controller.audit(a,req,mp,source,caseId)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).history(tenant,mp,source,caseId);
        verify(access,times(4)).currentTenant(a,req);
    }
    @Test void durableSchemaIsOneShotScopedAndAppendOnly() throws Exception {
        var sql=Files.readString(Path.of(
            "src/main/resources/db/migration/V93__cost_identity_collision_cases.sql"));
        for(var expected:List.of(
                "CREATE TABLE finance_cost_identity_case_v93",
                "UNIQUE(tenant_id,source_id,signal_code,candidate_id)",
                "REFERENCES finance_cost_source_v91(tenant_id,id)",
                "REFERENCES membership(tenant_id,user_id)",
                "reviewed_by<>opened_by",
                "NEW.reviewed_by=source_maker",
                "OLD.state<>'OPEN'",
                "NEW.state='OPEN'",
                "CREATE TRIGGER finance_cost_identity_case_audit_append_only_v93",
                "finance_cost_audit_append_only_v92()")) {
            assertTrue(sql.contains(expected),expected);
        }
        assertFalse(sql.contains("REALIZED_NET_PROFIT"));
        assertFalse(sql.contains("INSERT INTO finance_source_event"));
        assertFalse(sql.contains("CREATE EXTENSION"));
    }
    @Test void serverVerifiesActualCandidateIdentityNotJustEqualAmount() throws Exception {
        var src=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostIdentityCaseService.java"));
        for(var predicate:List.of("a.source_key=s.source_key",
            "a.report_reference=s.source_key",
            "f.source_event_key=s.source_key",
            "r.document_sha256=s.source_document_sha256",
            "c.source_document_sha256=s.source_document_sha256",
            "t.statement_ref=s.source_key"))
            assertTrue(src.contains(predicate),predicate);
        assertFalse(src.contains("f.amount=s.amount"));
        assertFalse(src.contains("a.spend_amount=s.amount"));
        assertFalse(src.contains("recognizedNetProfit=true"));
    }
}
