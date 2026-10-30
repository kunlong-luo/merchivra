package org.merchivra.erp.spapi;

import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Inputs rejected before LWA exchange must never touch secrets or the DB. */
class SellerOauthStateGateTest {
    @Test
    void forgedCallbackCannotClaimStateOrExchangeCode() {
        AtomicInteger exchanges=new AtomicInteger();
        var http=new LwaAuthorizationCodeClient(req -> {
            exchanges.incrementAndGet(); throw new AssertionError("LWA should never be called");
        }, URI.create("http://localhost:18080/token"), "fake-client", "fake-secret",
           "https://merchivra.example/api/v1/spapi/oauth/callback");
        var jdbc=mock(JdbcTemplate.class);
        var transactions=mock(TransactionTemplate.class);
        var service=new SellerOauthService(jdbc,transactions,
            new SellerTokenCipher(Base64.getEncoder().encodeToString(new byte[32]),1),
            http,Clock.systemUTC(),"amzn1.sellerapps.app.1234567890",
            "https://merchivra.example/api/v1/spapi/oauth/callback",
            Set.of("https://amazon.com"));
        String cookie="valid-cookie-state-that-is-at-least-forty-characters-0123456789";
        String forged="different-state-that-is-at-least-forty-characters-9876543210";
        assertThrows(ResponseStatusException.class, () ->
            service.complete(cookie,forged,"SELLER_EXAMPLE","fake-code-1234"));
        assertThrows(ResponseStatusException.class, () ->
            service.handoff(cookie,"https://attacker.invalid/apps/authorize/confirm/123",
                "amazon-opaque-state","SELLER_EXAMPLE",null));
        assertEquals(0,exchanges.get());
        verifyNoInteractions(jdbc,transactions);
    }

    @Test
    void constructorRejectsUnregisteredOrNonTlsRedirect() {
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var lwa=new LwaAuthorizationCodeClient(req -> {throw new AssertionError();},
            URI.create("http://localhost:18080/token"),
            "fake-client","fake-secret","https://merchivra.example/oauth");
        var cipher=new SellerTokenCipher(Base64.getEncoder().encodeToString(new byte[32]),1);
        assertThrows(IllegalArgumentException.class, () ->
            new SellerOauthService(jdbc,tx,cipher,lwa,Clock.systemUTC(),"bad-id",
                "https://merchivra.example/oauth",Set.of("https://amazon.com")));
        assertThrows(IllegalArgumentException.class, () ->
            new SellerOauthService(jdbc,tx,cipher,lwa,Clock.systemUTC(),
                "amzn1.sellerapps.app.1234567890",
                "http://merchivra.example/oauth",Set.of("https://amazon.com")));
    }
}
