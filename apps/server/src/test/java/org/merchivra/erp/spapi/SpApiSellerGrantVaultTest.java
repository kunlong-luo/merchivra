package org.merchivra.erp.spapi;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Guard against accidental use of unverified or foreign-account grants. */
class SpApiSellerGrantVaultTest {
    @Test
    void missingOrUnverifiedSellerNeverProvidesWorkerCredentials() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var cipher=new SellerTokenCipher(Base64.getEncoder().encodeToString(new byte[32]),1);
        var vault=new SpApiSellerGrantVault(jdbc,cipher);
        UUID tenant=UUID.randomUUID(), account=UUID.randomUUID();
        // Empty results represent: missing, revoked, PENDING_VERIFICATION,
        // disconnected account, suspended tenant or foreign Marketplace.
        assertTrue(vault.loadVerified(tenant,account,"ATVPDKIKX0DER").isEmpty());
        verify(jdbc).query(anyString(), any(RowMapper.class),
            eq(tenant),eq(account),eq("ATVPDKIKX0DER"));
        assertThrows(IllegalArgumentException.class, () ->
            vault.loadVerified(tenant,account,""));
    }
}
