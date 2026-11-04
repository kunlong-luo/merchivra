package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessOverviewServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final BusinessOverviewService service=new BusinessOverviewService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static BusinessOverviewService.Marketplace sample(String id,String code,
                                                                String currency) {
        var zeros=new BusinessOverviewService.Sources(0,0,0);
        return new BusinessOverviewService.Marketplace(
            id,code,"EU",currency,
            new BusinessOverviewService.Listings(0,0,0,0,zeros),
            new BusinessOverviewService.Orders(0,0,0,0,0,zeros),
            new BusinessOverviewService.Inventory(0,0,0,0,0,0,0,0,zeros),null);
    }

    @Test void ownerOverviewReadsFullCanonicalTablesNotUiFirstPageOrDemo() {
        var value=service.read(tenant);
        assertTrue(value.marketplaces().isEmpty());
        assertFalse(value.sourceSnapshotFrozen());
        assertFalse(value.crossCurrencyTotalsAvailable());
        assertFalse(value.financialSourceComplete());
        assertFalse(value.bankVerified());
        assertFalse(value.ledgerPosted());
        verify(jdbc).query(argThat((String sql)->sql.contains("FROM marketplace_listing")
                &&sql.contains("FROM sales_order")
                &&sql.contains("FROM fba_inventory_observation")
                &&sql.contains("DISTINCT ON (seller_sku_raw)")
                &&sql.contains("observed_at DESC NULLS LAST")
                &&sql.contains("FROM finance_profit_report_job")
                &&sql.contains("j.state='SUCCEEDED'")
                &&sql.contains("CASE WHEN j.state='SUCCEEDED' THEN")
                &&sql.contains("WHERE mp.tenant_id=?")
                &&sql.contains("LIMIT 101")),
            any(RowMapper.class),eq(tenant));
    }

    @Test void moreThanHundredMarketplacesFailInsteadOfOmittingTheRemainder() {
        List<BusinessOverviewService.Marketplace> many=new ArrayList<>();
        for(int i=0;i<101;i++)
            many.add(sample(UUID.randomUUID().toString(),"V89-"+i,"USD"));
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant))).thenReturn(many);
        var error=assertThrows(ResponseStatusException.class,()->service.read(tenant));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
    }

    @Test void csvReusesV64FormulaSafeEncoderAndNeverForgesUnknownContribution() {
        var a=sample(UUID.randomUUID().toString(),"=HYPERLINK(\"evil\")","USD");
        var b=sample(UUID.randomUUID().toString(),"@EXTERNAL","GBP");
        var snapshot=new BusinessOverviewService.Overview(
            "2026-11-04T00:00:00Z",false,false,false,false,
            false,true,List.of(a,b));
        String csv=BusinessOverviewService.csv(snapshot);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("original_currency"));
        assertTrue(csv.contains("'=HYPERLINK("));
        assertTrue(csv.contains("'@EXTERNAL"));
        assertTrue(csv.contains("\"USD\""));
        assertTrue(csv.contains("\"GBP\""));
        assertTrue(csv.contains("cross_currency_totals_available"));
        assertFalse(csv.contains("total_global_profit"));
    }

    @Test void ownerControllerScopesBothJsonAndCsvToServerSelectedTenant() {
        TenantAccessService access=mock(TenantAccessService.class);
        BusinessOverviewService reader=mock(BusinessOverviewService.class);
        var c=new BusinessOverviewController(access,reader);
        Authentication auth=mock(Authentication.class);
        HttpServletRequest request=new MockHttpServletRequest();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(reader.read(tenant)).thenReturn(new BusinessOverviewService.Overview(
            "2026-11-04T00:00:00Z",false,false,false,false,false,false,List.of()));
        var json=c.overview(auth,request);
        assertEquals(200,json.getStatusCode().value());
        assertTrue(json.getHeaders().getCacheControl().contains("no-store"));
        var csv=c.export(auth,request);
        assertEquals(200,csv.getStatusCode().value());
        assertTrue(csv.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(csv.getHeaders().getContentDisposition().toString().contains("attachment"));
        assertTrue(csv.getBody().contains("marketplace_uuid"));
        verify(access,times(2)).currentTenant(auth,request);
        verify(reader,times(2)).read(tenant);
        verifyNoMoreInteractions(access,reader);
    }
}
