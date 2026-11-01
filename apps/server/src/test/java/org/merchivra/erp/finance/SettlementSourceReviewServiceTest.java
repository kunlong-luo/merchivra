package org.merchivra.erp.finance;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SettlementSourceReviewServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SettlementReportComparisonService comparisons=
        mock(SettlementReportComparisonService.class);
    private final SettlementSourceReviewService service=
        new SettlementSourceReviewService(jdbc,comparisons);

    @Test void rejectsSelfApprovalAndUnusableRationalesBeforeDatabaseAccess(){
        UUID actor=UUID.randomUUID();
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->SettlementSourceReviewService.requireIndependentReviewer(actor,actor))
            .getStatusCode());
        SettlementSourceReviewService.requireIndependentReviewer(actor,UUID.randomUUID());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->SettlementSourceReviewService.validatedReason("     ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->SettlementSourceReviewService.validatedReason("   short    ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->SettlementSourceReviewService.validatedReason("valid note\nerror"))
            .getStatusCode());
        assertEquals("Reviewed source evidence 2026",
            SettlementSourceReviewService.validatedReason("  Reviewed source evidence 2026  "));
        assertThrows(ResponseStatusException.class,()->
            service.request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                "Valid review note"));
        verifyNoInteractions(jdbc);
    }

    @Test void digestIncludesAllIndependentFinanceSourcesWithoutPiiOrTokens(){
        String sql=SettlementSourceReviewService.EVIDENCE_SQL;
        assertTrue(sql.contains("r.document_sha256"));
        assertTrue(sql.contains("ref.reference_kind='SETTLEMENT_ID'"));
        assertTrue(sql.contains("ref.reference_value=r.settlement_reference"));
        assertTrue(sql.contains("f.seller_marketplace_id=r.seller_marketplace_id"));
        assertTrue(sql.contains("f.source_family='SPAPI_FINANCES'"));
        assertTrue(sql.contains("f.data_origin='LIVE'"));
        assertTrue(sql.contains("ORDER BY f.id"));
        assertTrue(sql.contains("r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?"));
        for(var forbidden:List.of("refresh_token","next_token","buyer_email","access_token"))
            assertFalse(sql.contains(forbidden));
    }

    @Test void reportsNotRequestedWithoutClaimingReconciliationOrBankVerification(){
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),report=UUID.randomUUID();
        when(comparisons.compare(tenant,mp,report)).thenReturn(
            new SettlementReportComparisonService.Comparison(
                "AMAZON-REPORT","SETTLEMENT-REF","USD","12.00","12.00","12.00",
                1,0,"SOURCE_AMOUNTS_AGREE_UNVERIFIED",false,false));
        doReturn(List.of("a".repeat(64))).when(jdbc).query(
            eq(SettlementSourceReviewService.EVIDENCE_SQL),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(tenant),eq(mp),eq(report));
        var result=service.status(tenant,mp,report);
        assertEquals("NOT_REQUESTED",result.state());
        assertEquals("SOURCE_AMOUNTS_AGREE_UNVERIFIED",result.sourceState());
        assertFalse(result.bankVerified());
        assertFalse(result.ledgerPosted());
        assertFalse(result.evidenceCurrent());
        verify(jdbc).query(eq(SettlementSourceReviewService.EVIDENCE_SQL),
            any(org.springframework.jdbc.core.RowMapper.class),eq(tenant),eq(mp),eq(report));
    }
}
