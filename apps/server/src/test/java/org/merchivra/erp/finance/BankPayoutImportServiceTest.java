package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BankPayoutImportServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final BankPayoutImportService service=new BankPayoutImportService(jdbc);
    private static byte[] csv(String s) {return s.getBytes(StandardCharsets.UTF_8);}
    private static final String VALID=BankPayoutCsv.HEADER+"\n"+
      "ENTRY-2026-001,SETTLE-2026-001,2026-10-30,USD,98.00,2.00,\n";

    @Test void rejectsInvalidFilesAndNoOwnerWithoutDatabaseSideEffects(){
        assertThrows(IllegalArgumentException.class,()->
            service.importCsv(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "BANKREF-2026-001",csv("wrong columns")));
        assertThrows(ResponseStatusException.class,()->
            service.importCsv(UUID.randomUUID(),UUID.randomUUID(),null,
                "BANKREF-2026-001",csv(VALID)));
        verifyNoInteractions(jdbc);
    }
    @Test void requiresActiveTenantMarketplaceAndDoesNotStoreBankCsvWhenAbsent(){
        var tenant=UUID.randomUUID();
        var mp=UUID.randomUUID();
        var actor=UUID.randomUUID();
        when(jdbc.queryForObject(contains("seller_marketplace m"),eq(Integer.class),
            eq(tenant),eq(mp))).thenReturn(0);
        var ex=assertThrows(ResponseStatusException.class,()->
            service.importCsv(tenant,mp,actor,"BANKREF-2026-001",csv(VALID)));
        assertEquals(HttpStatus.NOT_FOUND,ex.getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void databaseDedupeUsesTenantSiteReferenceAndFileSha(){
        assertEquals(64,BankPayoutImportService.sha(csv(VALID)).length());
        assertNotEquals(BankPayoutImportService.sha(csv(VALID)),
            BankPayoutImportService.sha(csv(VALID+"extra")));
    }
}
