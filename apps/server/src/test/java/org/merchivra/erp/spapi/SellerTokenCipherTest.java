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

    private static String key(byte fill) {
        byte[] bytes=new byte[32];
        java.util.Arrays.fill(bytes,fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void v9CanReadOldCiphertextAndRewrapUnderNewActiveKey() {
        UUID tenant=UUID.randomUUID(), account=UUID.randomUUID();
        var old=new SellerTokenCipher(key((byte)1),1);
        var first=old.encrypt(tenant,account,"SELLER-EXAMPLE","fake-grant");
        var next=SellerTokenCipher.configured(
            "1:"+key((byte)1)+",2:"+key((byte)2), "", 2);
        assertEquals(2,next.activeVersion());
        assertEquals("fake-grant",next.decrypt(tenant,account,"SELLER-EXAMPLE",first));
        var sealed=next.encrypt(tenant,account,"SELLER-EXAMPLE",
            next.decrypt(tenant,account,"SELLER-EXAMPLE",first));
        assertEquals(2,sealed.version());
        assertEquals("fake-grant",next.decrypt(tenant,account,"SELLER-EXAMPLE",sealed));
        assertThrows(IllegalStateException.class, () ->
            old.decrypt(tenant,account,"SELLER-EXAMPLE",sealed));
        assertThrows(SecurityException.class, () ->
            next.decrypt(tenant,UUID.randomUUID(),"SELLER-EXAMPLE",sealed));
    }

    @Test
    void keyringConfigFailsClosedOnMissingOrDuplicateVersionsAndTampering() {
        assertThrows(IllegalArgumentException.class, () ->
            SellerTokenCipher.configured("1:"+key((byte)1),"also-provided",1));
        assertThrows(IllegalArgumentException.class, () ->
            SellerTokenCipher.configured("1:"+key((byte)1)+",1:"+key((byte)2),"",1));
        assertThrows(IllegalArgumentException.class, () ->
            SellerTokenCipher.configured("1:"+key((byte)1),"",2));
        assertThrows(IllegalArgumentException.class, () ->
            SellerTokenCipher.configured("",key((byte)1),2));
        assertThrows(IllegalArgumentException.class, () ->
            SellerTokenCipher.configured("1:malformed","",1));
        var newOnly=SellerTokenCipher.configured("2:"+key((byte)2),"",2);
        var first=new SellerTokenCipher(key((byte)1),1)
            .encrypt(UUID.randomUUID(),UUID.randomUUID(),"SELLER","fake");
        assertThrows(IllegalStateException.class, () ->
            newOnly.decrypt(UUID.randomUUID(),UUID.randomUUID(),"SELLER",first));
    }
}
