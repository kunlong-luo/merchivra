package org.merchivra.erp.spapi;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellerTokenCipherTest {
    private static SellerTokenCipher cipher() {
        return new SellerTokenCipher(Base64.getEncoder().encodeToString(new byte[32]),1);
    }

    @Test
    void encryptsWithRandomNonceAndTenantSellerBoundAuthenticatedData() {
        var crypt = cipher();
        UUID tenant=UUID.randomUUID(), account=UUID.randomUUID();
        var first=crypt.encrypt(tenant,account,"SELLER-EXAMPLE","fake-refresh-not-real");
        var second=crypt.encrypt(tenant,account,"SELLER-EXAMPLE","fake-refresh-not-real");
        assertEquals(12,first.nonce().length);
        assertNotEquals(Base64.getEncoder().encodeToString(first.ciphertext()),
            Base64.getEncoder().encodeToString(second.ciphertext()));
        assertEquals("fake-refresh-not-real",
            crypt.decrypt(tenant,account,"SELLER-EXAMPLE",first));
        assertThrows(SecurityException.class, () ->
            crypt.decrypt(UUID.randomUUID(),account,"SELLER-EXAMPLE",first));
        assertThrows(SecurityException.class, () ->
            crypt.decrypt(tenant,UUID.randomUUID(),"SELLER-EXAMPLE",first));
        assertThrows(SecurityException.class, () ->
            crypt.decrypt(tenant,account,"SOME-OTHER-SELLER",first));
        var mutated=first.ciphertext().clone(); mutated[0]^=1;
        assertThrows(SecurityException.class, () ->
            crypt.decrypt(tenant,account,"SELLER-EXAMPLE",
                new SellerTokenCipher.Sealed(mutated,first.nonce(),1)));
    }

    @Test
    void rejectsIncorrectKeysAndUnknownVersion() {
        assertThrows(IllegalArgumentException.class, () -> new SellerTokenCipher("invalid",1));
        assertThrows(IllegalArgumentException.class, () ->
            new SellerTokenCipher(Base64.getEncoder().encodeToString(new byte[16]),1));
        var c=cipher();
        UUID t=UUID.randomUUID(), a=UUID.randomUUID();
        var token=c.encrypt(t,a,"SELLER-EXAMPLE","fake-refresh");
        assertThrows(IllegalStateException.class, () -> c.decrypt(t,a,"SELLER-EXAMPLE",
            new SellerTokenCipher.Sealed(token.ciphertext(),token.nonce(),2)));
    }
}
