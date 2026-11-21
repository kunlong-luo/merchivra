package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
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

class CostProfitReadinessServiceTest {
    private static LocalDate d(String x) {return LocalDate.parse(x);}
    @Test void halfOpenPeriodAndScopedCursorMustBeBounded() {
        CostProfitReadinessService.validate(d("2026-11-01"),d("2026-12-01"),null,null);
        CostProfitReadinessService.validate(d("2026-11-01"),d("2026-12-01"),
            d("2026-11-09"),UUID.randomUUID());
        for(var range:new LocalDate[][]{
            {d("2026-11-01"),d("2026-11-01")},
            {d("2026-12-01"),d("2026-11-01")},
            {d("2025-01-01"),d("2026-11-01")},
            {null,d("2026-11-01")}}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    CostProfitReadinessService.validate(range[0],range[1],null,null))
                    .getStatusCode());
        }
        assertThrows(ResponseStatusException.class,()->
            CostProfitReadinessService.validate(d("2026-11-01"),d("2026-12-01"),
                d("2026-11-09"),null));
        assertThrows(ResponseStatusException.class,()->
            CostProfitReadinessService.validate(d("2026-11-01"),d("2026-12-01"),
                null,UUID.randomUUID()));
        assertThrows(ResponseStatusException.class,()->
            CostProfitReadinessService.validate(d("2026-11-01"),d("2026-12-01"),
                d("2026-10-31"),UUID.randomUUID()));
        assertThrows(ResponseStatusException.class,()->
            CostProfitReadinessService.validate(d("2026-11-01"),d("2026-12-01"),
                d("2026-12-01"),UUID.randomUUID()));
    }
    @Test void controllerEnforcesSessionOwnerAndNeverCaches() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostProfitReadinessService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        LocalDate from=d("2026-11-01"),until=d("2026-12-01");
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var c=new CostProfitReadinessController(access,service);
        var result=c.report(auth,req,mp,from,until,null,null);
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(service).report(tenant,mp,from,until,null,null);
    }
    @Test void originalCurrencyAggregationCannotBecomeVerifiedProfit() throws Exception {
        var sql=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostProfitReadinessService.java"));
        for(var fragment:new String[]{
            "b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')",
            "b.state='APPROVED_UNVERIFIED'",
            "GROUP BY category,currency_code","sum(amount) AS claimed",
            "s.business_date>=? AND s.business_date<?",
            "(s.business_date,s.id)<(?,?)","LIMIT 51",
            "attribution.assessBatch(tenant,marketplace,",
            "barriers(tenant,marketplace,start,end)",
            "finance_cost_settlement_line_identity_v102 i",
            "finance_cost_settlement_line_audit_v94 a",
            "other.source_id<>l.source_id",
            "l.source_id=s.id",
            "LEFT JOIN finance_cost_settlement_line_identity_v102",
            "CROSS JOIN LATERAL",
            "count(*) FILTER(WHERE e.links=0) AS missing_link",
            "count(*) FILTER(WHERE e.capture_missing>0) AS missing_capture",
            "count(*) FILTER(WHERE e.shared>0) AS shared_source",
            "count(*) FILTER(WHERE e.links>1) AS multiple_claims",
            "c[0],List.copyOf(blockers),false",
            "localAttributionReasonCodes",
            "List.copyOf(missing),false,false,false,null",
            "isolation=Isolation.REPEATABLE_READ",
            "amount.subtract(approved)"})
            assertTrue(sql.contains(fragment),fragment);
        assertFalse(sql.contains("UPDATE finance_profit_report"));
        assertFalse(sql.contains("INSERT INTO finance_source_event"));
        assertFalse(sql.contains("MERCHIVRA_SPAPI_ENABLED=true"));
    }

    @Test void v183PeriodGateIsUnpagedAndNeverPromotesLocalEvidenceToProfit()
        throws Exception {
        var code=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostProfitReadinessService.java"));
        String global=code.substring(code.indexOf("private EvidenceBarriers barriers("),
            code.indexOf("@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)"));
        assertTrue(global.contains("s.business_date>=? AND s.business_date<?"));
        assertTrue(global.contains("s.tenant_id=? AND s.seller_marketplace_id=?"));
        assertTrue(global.contains("i.seller_marketplace_id=l.seller_marketplace_id"));
        assertTrue(global.contains("e.audit_missing"));
        assertFalse(global.contains("beforeDate"));
        assertFalse(global.contains("beforeId"));
        assertFalse(global.contains("LIMIT 51"));
        assertFalse(global.contains("INSERT INTO"));
        assertFalse(global.contains("UPDATE finance_"));
        assertTrue(global.contains("EIGHT_COST_CATEGORIES_NOT_EXTERNALLY_ATTESTED"));
        assertTrue(global.contains("BANK_SETTLEMENT_NOT_RECONCILED"));
        assertTrue(global.contains("List.copyOf(blockers),false"));
        var assessor=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostAttributionEvidenceService.java"));
        assertTrue(assessor.contains("localIdentityStatus"));
        assertTrue(assessor.contains("preview.status()"));
    }
}
