package org.merchivra.erp.spapi;

import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SellerGrantKeyRotationServiceTest {
    private static String key(int fill) {
        byte[] bytes=new byte[32];
        java.util.Arrays.fill(bytes,(byte)fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static TransactionTemplate transaction() {
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback=inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return tx;
    }

    @Test
    void absentOrRevokedGrantIsNeverDecryptedOrRewritten() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new SellerGrantKeyRotationService(jdbc,transaction(),
            new SellerTokenCipher(Map.of(1,key(1),2,key(2)),2));
        UUID tenant=UUID.randomUUID(), actor=UUID.randomUUID(), account=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant),eq(account),eq(actor)))
            .thenReturn(List.of());
        assertThrows(ResponseStatusException.class,
            () -> service.rotate(tenant,actor,account));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        assertThrows(IllegalArgumentException.class,
            () -> service.rotate(null,actor,account));
    }

    @Test
    void atomicallyRewrapsLegacyGrantWithoutChangingAuthorizationState() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        UUID tenant=UUID.randomUUID(), account=UUID.randomUUID(), actor=UUID.randomUUID();
        var oldCipher=new SellerTokenCipher(key(1),1);
        var sealed=oldCipher.encrypt(tenant,account,"SELLER-TEST","fake-refresh");
        var ring=new SellerTokenCipher(Map.of(1,key(1),2,key(2)),2);
        var rs=mock(ResultSet.class);
        when(rs.getString("seller_id")).thenReturn("SELLER-TEST");
        when(rs.getBytes("ciphertext")).thenReturn(sealed.ciphertext());
        when(rs.getBytes("nonce")).thenReturn(sealed.nonce());
        when(rs.getInt("key_version")).thenReturn(1);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant),eq(account),eq(actor)))
            .thenAnswer(inv -> {
                RowMapper<?> mapper=inv.getArgument(1);
                return List.of(mapper.mapRow(rs,0));
            });
        when(jdbc.update(contains("UPDATE spapi_seller_authorization"),
            any(byte[].class),any(byte[].class),eq(2),eq(tenant),eq(account),eq(1)))
            .thenReturn(1);
        var service=new SellerGrantKeyRotationService(jdbc,transaction(),ring);
        var result=service.rotate(tenant,actor,account);
        assertEquals("REWRAPPED",result.status());
        assertEquals(2,result.keyVersion());
        var ciphertext=org.mockito.ArgumentCaptor.forClass(byte[].class);
        var nonce=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(jdbc).update(contains("UPDATE spapi_seller_authorization"),
            ciphertext.capture(),nonce.capture(),eq(2),eq(tenant),eq(account),eq(1));
        assertEquals("fake-refresh",ring.decrypt(tenant,account,"SELLER-TEST",
            new SellerTokenCipher.Sealed(ciphertext.getValue(),nonce.getValue(),2)));
        verify(jdbc).update(contains("INSERT INTO spapi_oauth_audit"),
            any(UUID.class),eq(tenant),eq(actor),eq(account));
    }

    @Test
    void missingHistoricalKeyFailsClosedBeforeAnyDatabaseUpdate() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        UUID tenant=UUID.randomUUID(), account=UUID.randomUUID(), actor=UUID.randomUUID();
        var rs=mock(ResultSet.class);
        when(rs.getString("seller_id")).thenReturn("SELLER-TEST");
        when(rs.getBytes("ciphertext")).thenReturn(new byte[64]);
        when(rs.getBytes("nonce")).thenReturn(new byte[12]);
        when(rs.getInt("key_version")).thenReturn(1);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant),eq(account),eq(actor)))
            .thenAnswer(inv -> List.of(((RowMapper<?>)inv.getArgument(1)).mapRow(rs,0)));
        var service=new SellerGrantKeyRotationService(jdbc,transaction(),
            new SellerTokenCipher(Map.of(2,key(2)),2));
        assertThrows(IllegalStateException.class, () ->
            service.rotate(tenant,actor,account));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
