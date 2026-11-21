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

/** V182: explicit local classification, never a charge or profit attestation. */
class CostAttributionEvidenceV182Test {
    private static final UUID SOURCE=UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final List<Boolean> NO_OVERLAPS=List.of(
        false,false,false,false,false,false,false,false);

    private static CostIdentityReconciliationService.Preview preview(
        long linked,long shared,List<Boolean> overlaps) {
        return CostIdentityReconciliationService.summarize(
            SOURCE,"REVIEWED_UNVERIFIED",overlaps,linked,shared);
    }
    private static CostAttributionEvidenceService.Counts counts(
        long linked,long frozen,long reviewed,long audited,
        long auditedPotential,long exact,long incompatible,long shared) {
        return new CostAttributionEvidenceService.Counts(
            linked,frozen,linked-frozen,reviewed,audited,auditedPotential,
            exact,incompatible,shared);
    }
    private static CostAttributionEvidenceService.Assessment verdict(
        CostIdentityReconciliationService.Preview preview,
        CostAttributionEvidenceService.Counts counts) {
        return CostAttributionEvidenceService.classify(SOURCE,preview,counts);
    }
    private static void neverRelease(CostAttributionEvidenceService.Assessment verdict) {
        assertFalse(verdict.externalChargeVerified());
        assertFalse(verdict.duplicateDebitVerified());
        assertFalse(verdict.financialHoldCleared());
        assertFalse(verdict.netProfitEligible());
    }

    @Test void independentlyAuditedSingleFrozenExactMatchOnlySupportsLocalMetadata() {
        var result=verdict(preview(1,0,NO_OVERLAPS),
            counts(1,1,1,1,1,1,0,0));
        assertEquals("SUPPORTING_LOCAL_METADATA_UNVERIFIED",result.status());
        assertTrue(result.independentReviewAuditComplete());
        assertEquals(1,result.strongLocalMatchCount());
        assertFalse(result.localCollisionApprovalHeld());
        neverRelease(result);
    }
    @Test void noLinksAreUnknownNotZeroCosts() {
        var result=verdict(preview(0,0,NO_OVERLAPS),
            counts(0,0,0,0,0,0,0,0));
        assertEquals("UNPROVEN",result.status());
        assertTrue(result.reasonCodes().contains("NO_SETTLEMENT_LINE_ASSOCIATION"));
        assertFalse(result.independentReviewAuditComplete());
        neverRelease(result);
    }
    @Test void missingHistoricalCaptureCannotBeRecoveredByReviewOrMatch() {
        var result=verdict(preview(1,0,NO_OVERLAPS),
            counts(1,0,1,1,1,1,0,0));
        assertEquals("UNPROVEN",result.status());
        assertEquals(1,result.missingCaptureCount());
        assertTrue(result.reasonCodes().contains("CREATION_TIME_V102_CAPTURE_MISSING"));
        neverRelease(result);
    }
    @Test void reviewAndAppendOnlyAuditAreBothNecessary() {
        var pending=verdict(preview(1,0,NO_OVERLAPS),
            counts(1,1,0,0,0,1,0,0));
        assertEquals("UNPROVEN",pending.status());
        assertTrue(pending.reasonCodes().contains("INDEPENDENT_V94_REVIEW_PENDING"));
        var auditMissing=verdict(preview(1,0,NO_OVERLAPS),
            counts(1,1,1,0,0,1,0,0));
        assertEquals("UNPROVEN",auditMissing.status());
        assertTrue(auditMissing.reasonCodes().contains("V94_REVIEW_AUDIT_MISSING"));
        var escalated=verdict(preview(1,0,NO_OVERLAPS),
            counts(1,1,1,1,0,1,0,0));
        assertEquals("UNPROVEN",escalated.status());
        neverRelease(pending);neverRelease(auditMissing);neverRelease(escalated);
    }
    @Test void sharedSettlementLineTakesPrecedenceOverPositiveMetadata() {
        var result=verdict(preview(1,1,NO_OVERLAPS),
            counts(1,1,1,1,1,1,0,1));
        assertEquals("CONFLICT_EVIDENCE",result.status());
        assertTrue(result.localCollisionApprovalHeld());
        assertTrue(result.reasonCodes().contains("SHARED_SETTLEMENT_LINE_CLAIM"));
        neverRelease(result);
    }
    @Test void crossSourceIdentityCollisionBeatsPreviouslyApprovedLocalReview() {
        var signals=List.of(true,false,false,false,false,false,false,false);
        var result=verdict(preview(1,0,signals),
            counts(1,1,1,1,1,1,0,0));
        assertEquals("CONFLICT_EVIDENCE",result.status());
        assertTrue(result.reasonCodes().contains("POTENTIAL_CROSS_SOURCE_IDENTITY_OVERLAP"));
        neverRelease(result);
    }
    @Test void currencyOrNonDebitDirectionConflictBeatsOtherReviewSignals() {
        var result=verdict(preview(1,0,NO_OVERLAPS),
            counts(1,1,1,1,1,0,1,0));
        assertEquals("CONFLICT_EVIDENCE",result.status());
        assertTrue(result.reasonCodes().contains("CURRENCY_OR_DEBIT_DIRECTION_MISMATCH"));
        neverRelease(result);
    }
    @Test void multipleLinksNeverImplyAnExhaustiveSingleCostAttribution() {
        var result=verdict(preview(2,0,NO_OVERLAPS),
            counts(2,2,2,2,2,2,0,0));
        assertEquals("UNPROVEN",result.status());
        assertTrue(result.reasonCodes().contains("MULTIPLE_LINE_CLAIMS_REQUIRE_ALLOCATION_PROOF"));
        neverRelease(result);
    }
    @Test void inconsistentSnapshotFailsClosed() {
        var result=verdict(preview(2,0,NO_OVERLAPS),
            counts(1,1,1,1,1,1,0,0));
        assertEquals("UNPROVEN",result.status());
        assertTrue(result.reasonCodes().contains("LOCAL_EVIDENCE_COUNTS_INCONSISTENT"));
        neverRelease(result);
    }
    @Test void endpointUsesSessionTenantAndNeverCachesAttribution() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostAttributionEvidenceService.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=new CostAttributionEvidenceController(access,service)
            .get(auth,request,mp,SOURCE);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).assess(tenant,mp,SOURCE);
        verifyNoMoreInteractions(service);
    }
    @Test void inactiveOrOutOfScopeSourceIs404BeforeAnyEvidenceQuery() {
        var jdbc=mock(JdbcTemplate.class);
        var reconciler=new CostIdentityReconciliationService(jdbc);
        var service=new CostAttributionEvidenceService(jdbc,reconciler);
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,()->
                service.assess(UUID.randomUUID(),UUID.randomUUID(),SOURCE))
                .getStatusCode());
        // The existing scoped reconciliation lookup is the first DB call.
        verify(jdbc,times(1)).query(
            contains("finance_cost_source_v91"),
            any(org.springframework.jdbc.core.RowMapper.class),
            any(UUID.class),any(UUID.class),eq(SOURCE));
    }
    @Test void persistentEvidenceAndReviewRemainExistingV94V95V102Only() throws Exception {
        var sql=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostAttributionEvidenceService.java"));
        assertTrue(sql.contains("finance_cost_settlement_line_identity_v102 i"));
        assertTrue(sql.contains("finance_cost_settlement_line_audit_v94 a"));
        assertTrue(sql.contains("other.source_id<>l.source_id"));
        assertTrue(sql.contains("Isolation.REPEATABLE_READ"));
        assertFalse(sql.contains("INSERT INTO finance_"));
        assertFalse(sql.contains("UPDATE finance_"));
        assertFalse(sql.contains("DELETE FROM finance_"));
    }
}
