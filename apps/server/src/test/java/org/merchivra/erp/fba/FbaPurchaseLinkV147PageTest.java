package org.merchivra.erp.fba;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.purchasing.LocalKeysetPage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V147: rejected cursors cannot change tenant, marketplace, shipment or line scope. */
class FbaPurchaseLinkV147PageTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final FbaPurchaseLinkService service=new FbaPurchaseLinkService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID marketplace=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID shipment=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID inbound=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID poLine=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID id=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final String timestamp="2026-11-12T12:00:00Z";

    private void reject(Runnable read) {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,read::run).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void linkRejectsOutOfRangeLimitsUnknownStatesAndMalformedCursors() {
        reject(()->service.linksPage(tenant,marketplace,shipment,null,"ALL",0,null));
        reject(()->service.linksPage(tenant,marketplace,shipment,null,"ALL",101,null));
        reject(()->service.linksPage(tenant,marketplace,shipment,null,"PENDING",50,null));
        reject(()->service.linksPage(tenant,marketplace,shipment,null,"ALL",50,"/../"));
        reject(()->service.linksPage(tenant,marketplace,shipment,null,"ALL",50,"a".repeat(3000)));
    }
    @Test void linkCursorBindsTenantMarketplaceShipmentLineAndState() {
        String cursor=LocalKeysetPage.encode(tenant.toString(),marketplace.toString(),
            shipment.toString(),inbound.toString(),"PROPOSED",timestamp,id.toString());
        reject(()->service.linksPage(UUID.randomUUID(),marketplace,shipment,inbound,"PROPOSED",50,cursor));
        reject(()->service.linksPage(tenant,UUID.randomUUID(),shipment,inbound,"PROPOSED",50,cursor));
        reject(()->service.linksPage(tenant,marketplace,UUID.randomUUID(),inbound,"PROPOSED",50,cursor));
        reject(()->service.linksPage(tenant,marketplace,shipment,UUID.randomUUID(),"PROPOSED",50,cursor));
        reject(()->service.linksPage(tenant,marketplace,shipment,inbound,"CONFIRMED",50,cursor));
        reject(()->service.linksPage(tenant,marketplace,shipment,inbound,"PROPOSED",50,
            LocalKeysetPage.encode(tenant.toString(),marketplace.toString(),shipment.toString(),
            inbound.toString(),"PROPOSED","invalid-time",id.toString())));
    }
    @Test void candidateCursorBindsEveryScopeAndPurchaseLineFilter() {
        String cursor=LocalKeysetPage.encode(tenant.toString(),marketplace.toString(),
            shipment.toString(),inbound.toString(),poLine.toString(),timestamp,
            id.toString(),id.toString(),id.toString());
        reject(()->service.candidatesPage(UUID.randomUUID(),marketplace,shipment,inbound,poLine,50,cursor));
        reject(()->service.candidatesPage(tenant,UUID.randomUUID(),shipment,inbound,poLine,50,cursor));
        reject(()->service.candidatesPage(tenant,marketplace,UUID.randomUUID(),inbound,poLine,50,cursor));
        reject(()->service.candidatesPage(tenant,marketplace,shipment,UUID.randomUUID(),poLine,50,cursor));
        reject(()->service.candidatesPage(tenant,marketplace,shipment,inbound,UUID.randomUUID(),50,cursor));
        reject(()->service.candidatesPage(tenant,marketplace,shipment,inbound,poLine,50,
            LocalKeysetPage.encode(tenant.toString(),marketplace.toString(),
            shipment.toString(),inbound.toString(),poLine.toString(),timestamp,
            "not-uuid",id.toString(),id.toString())));
        reject(()->service.candidatesPage(tenant,marketplace,shipment,inbound,poLine,0,null));
        reject(()->service.candidatesPage(tenant,marketplace,shipment,inbound,poLine,101,null));
    }
}
