package org.merchivra.erp.spapi;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SellerOauthCallbackTest {
    private final Set<String> allow=Set.of("https://amazon.com",
        "https://sellercentral.amazon.com");

    @Test
    void acceptsOnlyExactAmazonCallbackOriginAndConfirmPath() {
        var url=SellerOauthService.verifiedCallback(
            "https://amazon.com/apps/authorize/confirm/example-app-id",allow);
        assertEquals("amazon.com",url.getHost());
        for(String bad : new String[]{
            "http://amazon.com/apps/authorize/confirm/x",
            "https://amazon.com.evil.test/apps/authorize/confirm/x",
            "https://evil.amazon.com/apps/authorize/confirm/x",
            "https://amazon.com:443/apps/authorize/confirm/x",
            "https://user@amazon.com/apps/authorize/confirm/x",
            "https://amazon.com/other/path",
            "https://amazon.com/apps/authorize/confirm/x?redirect_uri=https://bad.test",
            "https://amazon.com/apps/authorize/confirm/x#frag"
        }) {
            assertThrows(ResponseStatusException.class,
                () -> SellerOauthService.verifiedCallback(bad,allow),bad);
        }
    }

    @Test
    void stateIsHashedAndNotEqualToRawValue() {
        var state="sample-state-token-that-is-not-real-but-is-sufficiently-long-0001";
        var hashed=SellerOauthService.stateDigest(state);
        assertEquals(64,hashed.length());
        assertNotEquals(state,hashed);
        assertEquals(hashed,SellerOauthService.stateDigest(state));
        assertNotEquals(hashed,SellerOauthService.stateDigest(state+"another"));
    }
}
