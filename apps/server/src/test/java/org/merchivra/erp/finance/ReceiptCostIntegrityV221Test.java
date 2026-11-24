package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V221: two equal RR source snapshots may conceal committed A->B->A.
 * The independent monotonic V113 tenant revision must still reject the GET.
 * Real source triggers and committed PG18 visibility are checked in A3.
 */
class ReceiptCostIntegrityV221Test {
    static final UUID TENANT=ReceiptCostIntegrityV216Test.id(22101);
    static final UUID MARKETPLACE=ReceiptCostIntegrityV216Test.MARKETPLACE;
    static final UUID RECEIPT=ReceiptCostIntegrityV216Test.RECEIPT;
    static final UUID SOURCE=ReceiptCostIntegrityV216Test.SOURCE;

    record Fixture(JdbcTemplate jdbc, PlatformTransactionManager manager,
        ReceiptCostIntegrityServiceV216 service) {}

    @SuppressWarnings({"rawtypes","unchecked"})
    static Fixture fixture(List<ReceiptCostIntegrityGateV216.Source> rows,
                           long firstRevision,long secondRevision) {
        var jdbc=mock(JdbcTemplate.class);
        var reconciliation=mock(CostIdentityReconciliationService.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
            .thenAnswer(invocation->new SimpleTransactionStatus());
        when(jdbc.queryForObject(contains("SELECT count(*) FROM erp_purchase_receipt"),
            eq(Integer.class),eq(TENANT),eq(RECEIPT),eq(MARKETPLACE)))
            .thenReturn(1);
        doReturn(rows).when(jdbc).query(
            eq(ReceiptCostIntegrityServiceV216.RECEIPT_SCOPE_SQL),
            any(RowMapper.class),eq(TENANT),eq(RECEIPT),eq(MARKETPLACE));
        if(!rows.isEmpty()) {
            when(reconciliation.previewBatch(eq(TENANT),eq(MARKETPLACE),
                eq(rows.stream().map(
                    ReceiptCostIntegrityGateV216.Source::sourceId).toList())))
                .thenReturn(Map.of(SOURCE,ReceiptCostIntegrityV216Test.preview(false)));
        }
        when(jdbc.queryForObject(contains("finance_receipt_read_epoch_v113"),
            eq(Long.class),eq(TENANT)))
            .thenReturn(firstRevision,secondRevision);
        return new Fixture(jdbc,manager,
            new ReceiptCostIntegrityServiceV216(jdbc,reconciliation,manager));
    }

    static void checkTwoRRTransactions(Fixture f) {
        verify(f.manager(),times(2)).getTransaction(argThat(def->
            def.getIsolationLevel()==TransactionDefinition.ISOLATION_REPEATABLE_READ
            && def.isReadOnly()));
        verify(f.manager(),times(2)).commit(any());
        verify(f.jdbc(),times(2)).queryForObject(
            contains("finance_receipt_read_epoch_v113"),
            eq(Long.class),eq(TENANT));
    }

    @Test void committedABAEpochMustReturn409DespiteIdenticalSourceAmounts() {
        var f=fixture(List.of(ReceiptCostIntegrityV216Test.row()),18,20);
        var err=assertThrows(ResponseStatusException.class,
            ()->f.service().preview(TENANT,MARKETPLACE,RECEIPT));
        assertEquals(HttpStatus.CONFLICT,err.getStatusCode());
        assertTrue(err.getReason().contains("changed during independent read"));
        checkTwoRRTransactions(f);
    }

    @Test void changedAndThenRestoredEmptyPopulationAlsoReturns409() {
        var f=fixture(List.of(),7,9);
        var err=assertThrows(ResponseStatusException.class,
            ()->f.service().preview(TENANT,MARKETPLACE,RECEIPT));
        assertEquals(HttpStatus.CONFLICT,err.getStatusCode());
        checkTwoRRTransactions(f);
    }

    @Test void noCommittedChangesReturnOnlyUnverifiedLocalCandidates() {
        var f=fixture(List.of(ReceiptCostIntegrityV216Test.row()),44,44);
        var out=f.service().preview(TENANT,MARKETPLACE,RECEIPT);
        assertEquals(1,out.localPlanCount());
        assertEquals("35.000000",out.plans().getFirst().localReceiptCandidateAmount());
        assertFalse(out.receiptLandedCostVerified());
        assertFalse(out.actualBatchCogsVerified());
        assertNull(out.actualBatchCogs());
        assertNull(out.realizedNetProfit());
        checkTwoRRTransactions(f);
    }

    @Test void noHistoricalEpochDoesNotMagicallyVerifyCost() {
        var f=fixture(List.of(),0,0);
        var out=f.service().preview(TENANT,MARKETPLACE,RECEIPT);
        assertEquals(0,out.localPlanCount());
        assertTrue(out.blockerCodes().contains(
            "NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO"));
        assertNull(out.actualBatchCogs());
        checkTwoRRTransactions(f);
    }

    @Test void failClosedIfVersionIsAbsentOrInvalid() {
        var f=fixture(List.of(),-1,-1);
        var err=assertThrows(ResponseStatusException.class,
            ()->f.service().preview(TENANT,MARKETPLACE,RECEIPT));
        assertEquals(HttpStatus.CONFLICT,err.getStatusCode());
        verify(f.manager()).rollback(any());
    }

    @Test void scopeIsCheckedInsideRepeatableReadNotAheadOfIt() {
        var f=fixture(List.of(),3,3);
        when(f.jdbc().queryForObject(contains("SELECT count(*) FROM erp_purchase_receipt"),
            eq(Integer.class),eq(TENANT),eq(RECEIPT),eq(MARKETPLACE)))
            .thenReturn(1,0);
        var err=assertThrows(ResponseStatusException.class,
            ()->f.service().preview(TENANT,MARKETPLACE,RECEIPT));
        assertEquals(HttpStatus.NOT_FOUND,err.getStatusCode());
        verify(f.manager()).rollback(any());
        verify(f.manager()).commit(any());
    }
}
