package org.merchivra.erp.finance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V184 read-time performance improvements must not weaken V165/V182 gates. */
class CostAttributionBatchV184Test {
    private static final UUID T=UUID.randomUUID(),M=UUID.randomUUID();
    private static final UUID SOURCE=UUID.randomUUID();

    @Test void rejectsEmptyDuplicateNullAndOversizeBatchBeforeAnySql() {
        var jdbc=mock(JdbcTemplate.class);
        var reconciliation=new CostIdentityReconciliationService(jdbc);
        assertThrows(IllegalArgumentException.class,
            ()->reconciliation.previewBatch(T,M,List.of()));
        assertThrows(IllegalArgumentException.class,
            ()->reconciliation.previewBatch(T,M,List.of(SOURCE,SOURCE)));
        assertThrows(IllegalArgumentException.class,
            ()->reconciliation.previewBatch(T,M,
                java.util.Collections.nCopies(51,SOURCE)));
        assertThrows(IllegalArgumentException.class,
            ()->reconciliation.previewBatch(T,M,java.util.Arrays.asList(SOURCE,null)));
        verifyNoInteractions(jdbc);
    }
    @Test void allRequestedIdsMustBeInSameActiveTenantAndMarketplace() {
        var jdbc=mock(JdbcTemplate.class);
        var reconciliation=new CostIdentityReconciliationService(jdbc);
        // Empty JDBC result simulates an out-of-tenant or nonexistent cost.
        var error=assertThrows(ResponseStatusException.class,
            ()->reconciliation.previewBatch(T,M,List.of(SOURCE)));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
        verify(jdbc,times(1)).query(
            contains("s.id IN ("),any(org.springframework.jdbc.core.RowMapper.class),
            any(Object[].class));
    }
    @Test void groupSqlCountsOnlyRealV94LinksNotTheNullLeftJoinRow() throws Exception {
        var s=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostAttributionEvidenceService.java"));
        assertTrue(s.contains("count(l.id) AS links"));
        assertTrue(s.contains("count(l.id) FILTER(WHERE i.link_id IS NULL)"));
        assertTrue(s.contains("s.id IN ("));
        assertTrue(s.contains("GROUP BY s.id"));
        assertTrue(s.contains("other.source_id<>l.source_id"));
        assertTrue(s.contains("a.action=l.state AND a.actor_id=l.reviewed_by"));
        assertFalse(s.contains("INSERT INTO finance_"));
        assertFalse(s.contains("UPDATE finance_"));
    }
    @Test void pageUsesOneBatchWhileFullPeriodRemainsSeparate() throws Exception {
        var s=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostProfitReadinessService.java"));
        assertTrue(s.contains("attribution.assessBatch(tenant,marketplace,"));
        assertFalse(s.contains("attribution.assess(tenant,marketplace,r.id())"));
        assertTrue(s.contains("barriers(tenant,marketplace,start,end)"));
        assertTrue(s.contains("Isolation.REPEATABLE_READ"));
        assertTrue(s.contains("LIMIT 51"));
        assertTrue(s.contains("visible.isEmpty()"));
    }
}
