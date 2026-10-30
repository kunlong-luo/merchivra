package org.merchivra.erp.spapi;

import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.util.Base64;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellerConnectionVerificationServiceTest {
    private static SellerMarketplaceGateway.Participation p(String id,boolean active,boolean suspended) {
        return new SellerMarketplaceGateway.Participation(id,active,suspended);
    }
    @Test void requiresEveryConfiguredMarketplaceToBeActiveAndNotSuspended() {
        var configured=List.of("ATVPDKIKX0DER","A2EUQ1WTGCTBG2");
        assertNull(SellerConnectionVerificationService.validate(configured,List.of(
            p("ATVPDKIKX0DER",true,false),p("A2EUQ1WTGCTBG2",true,false))));
        assertEquals("MARKETPLACE_MISSING",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false))));
        assertEquals("NOT_PARTICIPATING",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false),
                p("A2EUQ1WTGCTBG2",false,false))));
        assertEquals("LISTINGS_SUSPENDED",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false),
                p("A2EUQ1WTGCTBG2",true,true))));
    }
    @Test void rejectsMalformedOrAmbiguousSellerResponse() {
        var configured=List.of("ATVPDKIKX0DER");
        assertEquals("EMPTY_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(configured,null));
        assertEquals("EMPTY_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(configured,List.of()));
        assertEquals("INVALID_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false),p("ATVPDKIKX0DER",true,false))));
        assertEquals("INVALID_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(
            configured,List.of(p("",true,false))));
    }
    @Test void unqualifiedOrMissingGrantDoesNotReachAmazon() {
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var gateway=mock(SellerMarketplaceGateway.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback=inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        var cipher=new SellerTokenCipher(Base64.getEncoder().encodeToString(new byte[32]),1);
        var service=new SellerConnectionVerificationService(jdbc,tx,cipher,gateway,Clock.systemUTC());
        assertThrows(ResponseStatusException.class, () ->
            service.verify(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
        verifyNoInteractions(gateway);
    }
}
