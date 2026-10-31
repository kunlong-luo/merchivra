package org.merchivra.erp.spapi;

import java.time.Clock;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Synthetic, no-network coverage for the full revoked -> reauthorized boundary.
 * The SQL predicates are checked because the mock never evaluates SQL itself.
 */
class SellerOauthReconnectTest {
    private final UUID tenant=UUID.randomUUID(), actor=UUID.randomUUID(), account=UUID.randomUUID();
    private final String seller="A1SYNTHETICSELLER";
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    private final LwaAuthorizationCodeClient lwa=mock(LwaAuthorizationCodeClient.class);
    private final SellerTokenCipher cipher=new SellerTokenCipher(
        Base64.getEncoder().encodeToString(new byte[32]),1);

    private SellerOauthService service() {
        when(tx.execute(any())).thenAnswer(inv->{
            TransactionCallback<?> cb=inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(inv->{
            Consumer<TransactionStatus> cb=inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
        return new SellerOauthService(jdbc,tx,cipher,lwa,Clock.systemUTC(),
            "amzn1.sellerapps.app.1234567890",
            "https://merchivra.example/api/v1/spapi/oauth/callback",
            Set.of("https://amazon.com"));
    }

    @Test void revokedStoreCanStartANewConsentOnlyWithMatchingBoundGrant() {
        var oauth=service();
        when(jdbc.query(contains("SELECT sa.region"),any(RowMapper.class),
            eq(tenant),eq(account),eq(tenant),eq(actor))).thenReturn(List.of("NA"));
        var start=oauth.start(tenant,actor,account,false);
        assertTrue(start.authorizationUrl().startsWith(
            "https://sellercentral.amazon.com/apps/authorize/consent?"));
        assertTrue(start.authorizationUrl().contains("state="));
        verify(jdbc).query(argThat(sql->sql.contains("sa.connection_status = 'REVOKED'")
            && sql.contains("g.status='REVOKED'")
            && sql.contains("sa.seller_id=g.seller_id")
            && sql.contains("NOT EXISTS (SELECT 1 FROM spapi_seller_authorization")),
            any(RowMapper.class),eq(tenant),eq(account),eq(tenant),eq(actor));
    }

    @Test void reauthRequiresExactSellerAndStoresFreshCiphertextAsPending() throws Exception {
        var oauth=service();
        String state="synthetic-oauth-state-at-least-forty-characters-1234567890";
        // The fake DB yields a correctly claimed one-time callback, just as
        // PostgreSQL UPDATE ... RETURNING would for an eligible grant.
        when(jdbc.query(contains("UPDATE spapi_oauth_attempt a SET consumed_at"),
            any(RowMapper.class),eq(SellerOauthService.stateDigest(state)),
            eq(seller),eq(seller))).thenAnswer(inv->{
                @SuppressWarnings("unchecked")
                RowMapper<Object> mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getObject("tenant_id",UUID.class)).thenReturn(tenant);
                when(rs.getObject("actor_user_id",UUID.class)).thenReturn(actor);
                when(rs.getObject("seller_account_id",UUID.class)).thenReturn(account);
                when(rs.getString("expected_seller_id")).thenReturn(seller);
                return List.of(mapper.mapRow(rs,0));
            });
        when(jdbc.query(contains("SELECT region,connection_status,seller_id"),
            any(RowMapper.class),eq(tenant),eq(account))).thenAnswer(inv->{
                @SuppressWarnings("unchecked")
                RowMapper<Object> mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString("region")).thenReturn("NA");
                when(rs.getString("connection_status")).thenReturn("REVOKED");
                when(rs.getString("seller_id")).thenReturn(seller);
                return List.of(mapper.mapRow(rs,0));
            });
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        when(lwa.exchange("synthetic-code-12345")).thenReturn("synthetic-refresh-token-2");
        oauth.complete(state,state,seller,"synthetic-code-12345");
        verify(jdbc).update(contains("UPDATE spapi_seller_authorization"),
            any(byte[].class),any(byte[].class),eq(1),
            eq(tenant),eq(account),eq(seller),eq("NA"));
        verify(jdbc).update(contains("UPDATE seller_account SET connection_status='AUTHORIZATION_PENDING'"),
            eq(tenant),eq(account),eq("REVOKED"));
        verify(jdbc,never()).update(contains("INSERT INTO spapi_seller_authorization"),
            any(Object[].class));
    }

    @Test void mismatchedCallbackStateNeverCallsLwaOrChangesGrant() {
        var oauth=service();
        var exc=assertThrows(ResponseStatusException.class,()->
            oauth.complete("synthetic-cookie-state-at-least-forty-characters-123456",
                "different-returned-state-at-least-forty-characters-654321",
                seller,"synthetic-code-12345"));
        assertEquals(400,exc.getStatusCode().value());
        verifyNoInteractions(jdbc,lwa);
    }

    @Test void revokingAlsoInvalidatesEveryDependentRoleAndMarketplaceProof() {
        var oauth=service();
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        oauth.revoke(tenant,actor,account);
        verify(jdbc).update(contains("SET status='REVOKED',ciphertext=NULL,nonce=NULL"),
            eq(tenant),eq(account));
        verify(jdbc).update(contains("DELETE FROM spapi_marketplace_verification"),
            eq(tenant),eq(account));
        verify(jdbc).update(contains("UPDATE spapi_orders_role_evidence SET revoked_at=now()"),
            eq(tenant),eq(account));
        verify(jdbc).update(contains("UPDATE spapi_orders_role_review_request SET state='STALE'"),
            eq(tenant),eq(account));
    }

    @Test void failedAccountStateTransitionRollsBackCredentialRevocation() {
        var oauth=service();
        when(jdbc.update(contains("UPDATE spapi_seller_authorization"),any(Object[].class)))
            .thenReturn(1);
        when(jdbc.update(contains("UPDATE seller_account SET connection_status='REVOKED'"),
            any(Object[].class))).thenReturn(0);
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->oauth.revoke(tenant,actor,account)).getStatusCode().value());
        verify(jdbc,never()).update(contains("DELETE FROM spapi_marketplace_verification"),
            any(Object[].class));
    }
}
