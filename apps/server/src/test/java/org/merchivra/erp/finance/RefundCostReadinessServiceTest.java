package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundCostReadinessServiceTest {
    static LocalDate d(String s) {return LocalDate.parse(s);}
    @Test void utcRangeAndStableTimestampCursorAreValidated() {
        RefundCostReadinessService.validate(d("2025-03-01"),d("2025-04-01"),null,null);
        RefundCostReadinessService.validate(d("2025-03-01"),d("2025-04-01"),
            Instant.parse("2025-03-08T15:32:16.123Z"),UUID.randomUUID());
        for(var range:new LocalDate[][]{
            {d("2025-03-01"),d("2025-03-01")},
            {d("2025-04-01"),d("2025-03-01")},
            {d("2025-01-01"),d("2026-04-01")},
            {null,d("2025-04-01")}}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    RefundCostReadinessService.validate(range[0],range[1],null,null))
                    .getStatusCode());
        }
        assertThrows(ResponseStatusException.class,()->
            RefundCostReadinessService.validate(d("2025-03-01"),d("2025-04-01"),
                Instant.parse("2025-03-08T00:00:00Z"),null));
        assertThrows(ResponseStatusException.class,()->
            RefundCostReadinessService.validate(d("2025-03-01"),d("2025-04-01"),
                null,UUID.randomUUID()));
        for(var cursor:new String[]{"2025-02-28T23:59:59Z","2025-04-01T00:00:00Z"}) {
            assertThrows(ResponseStatusException.class,()->
                RefundCostReadinessService.validate(d("2025-03-01"),d("2025-04-01"),
                    Instant.parse(cursor),UUID.randomUUID()));
        }
    }
    @Test void ownerSessionAndNoStoreAreServerAuthoritative() {
        var access=mock(TenantAccessService.class);
        var service=mock(RefundCostReadinessService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        var from=d("2025-03-01");var until=d("2025-04-01");
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var controller=new RefundCostReadinessController(access,service);
        var response=controller.report(auth,req,mp,from,until,null,null);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).report(tenant,mp,from,until,null,null);
    }
    @Test void sourceRefundIsSignedAndNeverCoercedToActualCogsOrBankProfit()
        throws Exception {
        assertEquals("-12.500000",RefundCostReadinessService.amount(
            new java.math.BigDecimal("-12.5")));
        String java=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/RefundCostReadinessService.java"));
        for(var guard:new String[]{
            "category='PRODUCT_REFUND'","source_family='SPAPI_FINANCES'",
            "data_origin='LIVE'","GROUP BY currency_code",
            "posted_at>=? AND posted_at<?","(f.posted_at,f.id)<(?,?)",
            "ORDER BY f.posted_at DESC,f.id DESC LIMIT 51",
            "recovery.preview(tenant,mp,src.id())",
            "false,false,null","List.copyOf(buckets)",
            "TransactionDefinition.ISOLATION_REPEATABLE_READ",
            "snapshotTx.execute(ignored->",
            "for(var source:snapshot.visible())events.add(describe"})
            assertTrue(java.contains(guard),guard);
        assertFalse(java.contains("@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)"));
        assertFalse(java.contains("UPDATE finance_source_event"));
        assertFalse(java.contains("UPDATE finance_profit_report"));
        assertFalse(java.contains("INSERT INTO finance_profit_report"));
        assertFalse(java.contains("INSERT INTO stock_movement"));
    }
}
