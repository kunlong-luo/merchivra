package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SettlementCategoryReviewV276Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SettlementReportComparisonService comparisons=mock(SettlementReportComparisonService.class);
    private final RefundRecoveryService owners=mock(RefundRecoveryService.class);
    private final SettlementCategoryReviewV276 service=new SettlementCategoryReviewV276(jdbc,comparisons,owners);
    private final UUID tenant=UUID.randomUUID(),market=UUID.randomUUID(),report=UUID.randomUUID(),
        maker=UUID.randomUUID(),checker=UUID.randomUUID(),creator=UUID.randomUUID();
    private final String mapping="a".repeat(64),evidence="b".repeat(64);
    private final String reason="Synthetic independent review";
    private final OffsetDateTime now=OffsetDateTime.parse("2026-11-25T00:00:00Z");
    private void preview(String sha) {
        var result=new SettlementCategoryPreviewV273.Preview("LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED",
            "SOURCE_AMOUNTS_AGREE_UNVERIFIED","USD",0,0,0,0,List.of(),false,false,false,null);
        when(comparisons.previewSavedCategoryMapping(tenant,market,report,mapping)).thenReturn(
            new SettlementReportComparisonService.SavedMappingPreview(mapping,result,sha,false));
    }
    @Test void staleEvidenceStopsBeforeWriting() {
        preview("c".repeat(64));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.request(
            tenant,market,report,mapping,evidence,maker,reason)).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
    @Test void independentReviewerCannotBeMakerOrDraftCreator() {
        for(UUID actor:List.of(maker,creator))
            assertEquals(403,assertThrows(ResponseStatusException.class,()->
                SettlementCategoryReviewV276.independent(maker,creator,actor)).getStatusCode().value());
        SettlementCategoryReviewV276.independent(maker,creator,checker);
    }
    @Test void noApprovalWithoutRecordedRequest() {
        preview(evidence);
        doReturn(List.of()).when(jdbc).query(eq(SettlementCategoryReviewV276.READ_REQUEST_SQL),
            any(RowMapper.class),eq(tenant),eq(market),eq(report),eq(mapping),eq(evidence));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.approve(
            tenant,market,report,mapping,evidence,checker,reason)).getStatusCode().value());
        verify(jdbc,never()).update(eq(SettlementCategoryReviewV276.APPROVAL_SQL),
            any(),any(),any(),any(),any(),any(),any());
    }
    @Test void exactKeysAndNoMutableWrite() {
        for(String sql:List.of(SettlementCategoryReviewV276.REQUEST_SQL,SettlementCategoryReviewV276.APPROVAL_SQL)) {
            assertTrue(sql.contains("ON CONFLICT (tenant_id,report_id,mapping_sha256,evidence_sha256) DO NOTHING"));
            assertFalse(sql.contains("UPDATE"));
        }
        for(String sql:List.of(SettlementCategoryReviewV276.READ_REQUEST_SQL,SettlementCategoryReviewV276.READ_APPROVAL_SQL))
            assertTrue(sql.contains("r.seller_marketplace_id=?"));
    }
}
