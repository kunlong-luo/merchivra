package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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

class CostSettlementLineEvidenceServiceTest {
    private static final BigDecimal COST=new BigDecimal("12.340000");

    @Test void referenceAmountAndCurrencyNeverEstablishChargeIdentity() {
        var s=CostSettlementLineEvidenceService.class;
        assertEquals("SIMILAR_REFERENCE_AND_AMOUNT",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                "ORDER-REF1",new BigDecimal("-12.34"),"USD"));
        assertEquals("SIMILAR_REFERENCE_ONLY",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                "ORDER-REF1",new BigDecimal("-9.99"),"USD"));
        assertEquals("AMOUNT_ONLY_NO_IDENTITY",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                "UNRELATED",new BigDecimal("-12.34"),"USD"));
        assertEquals("UNRELATED_LINE_NO_EVIDENCE",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                null,new BigDecimal("-9.99"),"USD"));
        assertEquals("CURRENCY_MISMATCH",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                "ORDER-REF1",new BigDecimal("-12.34"),"EUR"));
        assertEquals("CREDIT_OR_ZERO_NOT_DEBIT",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                "ORDER-REF1",new BigDecimal("12.34"),"USD"));
        assertEquals("CREDIT_OR_ZERO_NOT_DEBIT",
            CostSettlementLineEvidenceService.similarity("ORDER-REF1",COST,"USD",
                "ORDER-REF1",BigDecimal.ZERO,"USD"));
        assertThrows(IllegalArgumentException.class,()->
            CostSettlementLineEvidenceService.similarity("ref",BigDecimal.ZERO,
                "USD","ref",new BigDecimal("-5"),"USD"));
    }

    @Test void independentReviewerCannotBeSourceMakerOrLinkRecorder() {
        UUID maker=UUID.randomUUID(),recorder=UUID.randomUUID(),
            reviewer=UUID.randomUUID();
        for(String state:List.of("ESCALATED_NEEDS_PROOF",
            "POTENTIAL_DUPLICATE_UNVERIFIED","NOT_SAME_CHARGE_UNVERIFIED"))
            CostSettlementLineEvidenceService.independent(maker,recorder,
                reviewer,state,"Independent financial metadata review");
        for(UUID self:List.of(maker,recorder))
            assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,()->
                    CostSettlementLineEvidenceService.independent(maker,recorder,
                        self,"ESCALATED_NEEDS_PROOF",
                        "This self-review must be rejected")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->
                CostSettlementLineEvidenceService.independent(maker,recorder,
                    null,"ESCALATED_NEEDS_PROOF",
                    "Unknown reviewer is not permitted")).getStatusCode());
        for(String state:List.of("CLEAR","VERIFIED_DUPLICATE","REVIEWED_COST",
            "RECOGNIZED_NET_PROFIT","RELEASE_HOLD"))
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    CostSettlementLineEvidenceService.independent(maker,recorder,
                        reviewer,state,"Unsupported trusted financial claim"))
                    .getStatusCode());
    }

    @Test void invalidWritesFailBeforeDbAccess() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CostSettlementLineEvidenceService(jdbc);
        UUID t=UUID.randomUUID(),mp=UUID.randomUUID(),src=UUID.randomUUID();
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
                service.attach(t,mp,src,UUID.randomUUID(),1,null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                service.attach(t,mp,src,UUID.randomUUID(),0,
                    UUID.randomUUID())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                service.attach(t,mp,src,null,1,
                    UUID.randomUUID())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                service.decide(t,mp,src,UUID.randomUUID(),UUID.randomUUID(),
                    "CLEAR","Cannot bypass settlement reconciliation")).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void ownerSessionPathsDoNotAcceptTenantFromClient() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostSettlementLineEvidenceService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID t=UUID.randomUUID(),mp=UUID.randomUUID(),src=UUID.randomUUID(),
            report=UUID.randomUUID(),link=UUID.randomUUID(),actor=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(t);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var controller=new CostSettlementLineEvidenceController(access,service);
        assertTrue(controller.attach(auth,req,mp,src,
            new CostSettlementLineEvidenceController.Candidate(report,4))
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).attach(t,mp,src,report,4,actor);
        assertTrue(controller.list(auth,req,mp,src).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).list(t,mp,src);
        var review=new CostSettlementLineEvidenceController.Review(
            "POTENTIAL_DUPLICATE_UNVERIFIED","Metadata requires third-party invoice");
        assertTrue(controller.review(auth,req,mp,src,link,review).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).decide(t,mp,src,link,actor,review.disposition(),review.reason());
        assertTrue(controller.audit(auth,req,mp,src,link).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).history(t,mp,src,link);
        verify(access,times(4)).currentTenant(auth,req);
    }

    @Test void dbRequiresActualTenantScopedLineAndForbidsRewrites() throws Exception {
        var sql=Files.readString(Path.of(
            "src/main/resources/db/migration/V94__cost_settlement_line_evidence_links.sql"));
        for(var required:List.of(
            "REFERENCES finance_cost_source_v91(tenant_id,seller_marketplace_id,id)",
            "REFERENCES spapi_settlement_report(tenant_id,seller_marketplace_id,id)",
            "REFERENCES spapi_settlement_report_line(tenant_id,report_id,line_number)",
            "UNIQUE(tenant_id,source_id,report_id,line_number)",
            "finance_cost_settlement_line_link_guard_v94",
            "NEW.reviewed_by=s.created_by","NEW.reviewed_by=OLD.recorded_by",
            "OLD.state<>'OPEN'","NEW.state='OPEN'",
            "CREATE TRIGGER finance_cost_settlement_line_audit_append_only_v94"))
            assertTrue(sql.contains(required),required);
        assertFalse(sql.contains("INSERT INTO finance_source_event"));
        assertFalse(sql.contains("UPDATE profit_report"));
        assertFalse(sql.contains("DROP TABLE"));
    }
}
