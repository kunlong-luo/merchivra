package org.merchivra.erp.spapi;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpApiRedactionTest {
    @Test
    void structuredDiagnosticsCannotPrintSecretsOrAmazonPayloads() {
        var seller=new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),
            "SELLER_SECRET", "ATVPDKIKX0DER",SpApiRegion.NA,"REFRESH_SECRET",Set.of());
        assertFalse(seller.toString().contains("REFRESH_SECRET"));
        assertFalse(seller.toString().contains("SELLER_SECRET"));
        var request=new SpApiRequest(SpApiRegion.NA,SpApiOperation.CREATE_REPORT,
            Map.of(),Map.of(),"{\"token\":\"BODY_SECRET\"}",Set.of());
        assertFalse(request.toString().contains("BODY_SECRET"));
        var response=new SpApiResponse(200,"BUYER_SECRET",Map.of("secret","HEADER_SECRET"));
        assertFalse(response.toString().contains("BUYER_SECRET"));
        assertFalse(response.toString().contains("HEADER_SECRET"));
        var start=new SellerOauthService.Start("STATE_SECRET",
            "https://sellercentral.amazon.com/auth?state=STATE_SECRET","2026-10-31T00:00:00Z");
        assertFalse(start.toString().contains("STATE_SECRET"));
        var link=new SellerOauthController.StartLink(
            "https://sellercentral.amazon.com/auth?state=STATE_SECRET",
            "2026-10-31T00:00:00Z");
        assertFalse(link.toString().contains("STATE_SECRET"));
    }
}
