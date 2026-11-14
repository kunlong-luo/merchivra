package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
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

class CostSourceReviewServiceTest {
    private static final String SHA="a".repeat(64);
    private static CostSourceReviewService.Draft draft() {
        return new CostSourceReviewService.Draft("INBOUND_FREIGHT","MANUAL_INVOICE",
            "INVOICE-2026-0001",SHA,LocalDate.of(2026,11,10),"12.340000","USD");
    }
    @Test void acceptsOnlyPositiveOriginalCurrencyAmountAndHonestManualEvidence() {
        assertEquals(new BigDecimal("12.340000"),
            CostSourceReviewService.validate(draft()));
        var d=draft();
        for(var bad:List.of(
            new CostSourceReviewService.Draft("NOT_A_COST",d.sourceKind(),
                d.sourceKey(),d.documentSha256(),d.businessDate(),d.amount(),d.currency()),
            new CostSourceReviewService.Draft(d.category(),"FINANCES",
                d.sourceKey(),d.documentSha256(),d.businessDate(),d.amount(),d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                "x",d.documentSha256(),d.businessDate(),d.amount(),d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                d.sourceKey(),"0".repeat(64),d.businessDate(),d.amount(),d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                d.sourceKey(),"not-a-hash",d.businessDate(),d.amount(),d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                d.sourceKey(),d.documentSha256(),d.businessDate(),"0",d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                d.sourceKey(),d.documentSha256(),d.businessDate(),"-0.1",d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                d.sourceKey(),d.documentSha256(),d.businessDate(),"0.0000001",d.currency()),
            new CostSourceReviewService.Draft(d.category(),d.sourceKind(),
                d.sourceKey(),d.documentSha256(),d.businessDate(),"15", "usd")
        )) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->CostSourceReviewService.validate(bad)).getStatusCode());
        }
    }
    @Test void badAndUnauthorizedDraftNeverTouchDatabase() {
        var jdbc=mock(JdbcTemplate.class);
        var s=new CostSourceReviewService(jdbc);
        var tenant=UUID.randomUUID();var mp=UUID.randomUUID();var actor=UUID.randomUUID();
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->s.create(tenant,mp,null,draft())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->s.create(tenant,mp,actor,new CostSourceReviewService.Draft(
                "ADS_SPEND","MANUAL_INVOICE","incomplete",
                SHA,LocalDate.of(2026,11,10),"-2","USD"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void reviewerMustBeIndependentAndDecisionMustHaveAuditableReason() {
        UUID maker=UUID.randomUUID(),checker=UUID.randomUUID();
        CostSourceReviewService.independent(maker,checker,
            "Reviewed invoice metadata against local evidence");
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                CostSourceReviewService.independent(maker,maker,"Self approval attempt"))
                .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->
                CostSourceReviewService.independent(maker,null,"Unknown reviewer attempt"))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                CostSourceReviewService.independent(maker,checker,"short"))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                CostSourceReviewService.independent(maker,checker,"line one long\nline two"))
                .getStatusCode());
    }
    @Test void ownerScopeFromSessionNotRequestBodyAndNoStoreResponse() {
        var access=mock(TenantAccessService.class);
        var s=mock(CostSourceReviewService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),source=UUID.randomUUID(),
            maker=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(maker);
        var controller=new CostSourceReviewController(access,s);
        assertTrue(controller.create(auth,req,mp,draft()).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(s).create(tenant,mp,maker,draft());
        assertTrue(controller.list(auth,req,mp).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(s).list(tenant,mp);
        var decision=new CostSourceReviewController.Decision(true,
            "Independent local invoice metadata review");
        assertTrue(controller.review(auth,req,mp,source,decision).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(s).decide(tenant,mp,source,maker,true,decision.reason());
        assertTrue(controller.audit(auth,req,mp,source).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(s).history(tenant,mp,source);
        verify(access,times(4)).currentTenant(auth,req);
    }
    @Test void schemaHasImmutableSourceMetadataAndReviewerSeparation() throws Exception {
        var path=java.nio.file.Path.of(
            "src/main/resources/db/migration/V91__manual_cost_source_review.sql");
        String sql=java.nio.file.Files.readString(path);
        for(String required:List.of("CREATE TABLE finance_cost_source_v91",
            "UNIQUE(tenant_id,seller_marketplace_id,source_kind,source_key)",
            "REFERENCES seller_marketplace(tenant_id,id)",
            "reviewed_by<>created_by",
            "CREATE TRIGGER finance_cost_source_immutable_guard_v91",
            "OLD.review_state <> 'STAGED'",
            "CREATE TABLE finance_cost_source_audit_v91",
            "evidence_origin='MANUAL_UNVERIFIED'",
            "REVIEWED_UNVERIFIED","CHECK(amount > 0",
            "source_document_sha256")) {
            assertTrue(sql.contains(required),required);
        }
        assertFalse(sql.contains("INSERT INTO finance_profit_report"));
        assertFalse(sql.contains("CREATE EXTENSION"));
    }
}
