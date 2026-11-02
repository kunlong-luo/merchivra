package org.merchivra.erp.fba;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FbaPurchaseLinkServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final FbaPurchaseLinkService proof=new FbaPurchaseLinkService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID market=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID shipment=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID actor=UUID.fromString("44444444-4444-4444-8444-444444444444");
    @Test void proofCannotBeBlankShortControlOrOverlong(){
        for(String value:new String[]{null,"   ","too short","V75 has\nnewline",
            "   V75 \t hidden control   ","x".repeat(201)}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                    ()->FbaPurchaseLinkService.proof(value)).getStatusCode());
        }
        assertEquals("V75 reviewed purchase evidence",
            FbaPurchaseLinkService.proof("  V75 reviewed purchase evidence  "));
    }
    @Test void unverifiedAndMissingProofCannotBeProposed(){
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->proof.propose(
                tenant,market,shipment,actor,null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->proof.propose(
                tenant,market,shipment,actor,new FbaPurchaseLinkService.Propose(
                    null,UUID.randomUUID(),UUID.randomUUID(),null,
                    "V75 test identity reference"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void transitionsRejectUnknownActionAndUnboundedReasonWithoutSql(){
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->proof.change(
                tenant,market,actor,UUID.randomUUID(),"AUTO_VERIFY",
                "V75 valid reason sample")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->proof.change(
                tenant,market,actor,UUID.randomUUID(),"CONFIRMED",
                "short")).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
