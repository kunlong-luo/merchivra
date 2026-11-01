package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankPayoutComparisonServiceTest {
    private static BigDecimal v(String n){return n==null?null:new BigDecimal(n);}
    @Test void classificationIsAlwaysManualAndConservative() {
        assertEquals("NO_MANUAL_BANK_ENTRY",
            BankPayoutComparisonService.decision(1,0,true,v("100"),"USD",null,null,null,null));
        assertEquals("SOURCE_REVIEW_REQUIRED",
            BankPayoutComparisonService.decision(1,1,false,v("100"),"USD","USD",v("98"),v("2"),null));
        assertEquals("AMBIGUOUS_BANK_ENTRY",
            BankPayoutComparisonService.decision(1,2,true,v("100"),"USD","USD",v("98"),v("2"),null));
        assertEquals("DUPLICATE_REPORT_SETTLEMENT_ID",
            BankPayoutComparisonService.decision(2,1,true,v("100"),"USD","USD",v("98"),v("2"),null));
        assertEquals("MANUAL_AMOUNT_MATCH_UNVERIFIED",
            BankPayoutComparisonService.decision(1,1,true,v("100"),"USD","USD",v("98"),v("2"),null));
        assertEquals("SAME_CURRENCY_AMOUNT_DIFFERENCE",
            BankPayoutComparisonService.decision(1,1,true,v("100"),"USD","USD",v("97"),v("2"),null));
        assertEquals("SAME_CURRENCY_RATE_CONFLICT",
            BankPayoutComparisonService.decision(1,1,true,v("100"),"USD","USD",v("98"),v("2"),v("0.90")));
        assertEquals("FX_RATE_REQUIRED",
            BankPayoutComparisonService.decision(1,1,true,v("100"),"USD","EUR",v("90"),v("0"),null));
        assertEquals("MANUAL_FX_MATCH_UNVERIFIED",
            BankPayoutComparisonService.decision(1,1,true,v("100"),"USD","EUR",v("90"),v("0"),v("0.9")));
        assertEquals("MANUAL_FX_AMOUNT_DIFFERENCE",
            BankPayoutComparisonService.decision(1,1,true,v("100"),"USD","EUR",v("80"),v("0"),v("0.9")));
        assertEquals("REPORT_NET_MISSING",
            BankPayoutComparisonService.decision(1,1,true,null,"USD","USD",v("98"),v("2"),null));
    }

    @Test void comparingBankUsesTenantSiteExactSettlementRefAndNeverBankCredentials() {
        var sql=BankPayoutComparisonService.LOOKUP;
        assertTrue(sql.contains("r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?"));
        assertTrue(sql.contains("b.seller_marketplace_id=r.seller_marketplace_id"));
        assertTrue(sql.contains("b.settlement_ref=r.settlement_reference"));
        assertTrue(sql.contains("other.tenant_id=r.tenant_id"));
        assertFalse(sql.contains("bank_account"));
        assertFalse(sql.contains("bank_token"));
    }

    @Test void bankImportUsesOwnerSessionScopeNotBrowserTenantOrBankAccount() {
        var access=mock(TenantAccessService.class);
        var imports=mock(BankPayoutImportService.class);
        var comparisons=mock(BankPayoutComparisonService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        var tenant=UUID.randomUUID();
        var market=UUID.randomUUID();
        var actor=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var controller=new BankPayoutController(access,imports,comparisons);
        byte[] csv="entry_id,settlement_id,value_date,currency,credit_amount,withheld_fee,fx_rate\n".getBytes();
        var response=controller.upload(auth,req,market,"STATEMENT-12345",csv);
        verify(imports).importCsv(tenant,market,actor,"STATEMENT-12345",csv);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var report=UUID.randomUUID();
        controller.compare(auth,req,market,report);
        verify(comparisons).compare(tenant,market,report);
    }
}
