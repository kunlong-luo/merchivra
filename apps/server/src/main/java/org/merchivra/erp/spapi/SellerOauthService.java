package org.merchivra.erp.spapi;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Amazon website authorization: start -> login URI handoff -> OAuth redirect.
 * The short-lived browser handoff cookie accompanies only top-level Lax GETs.
 * State is hashed at rest, single-use, tied to tenant/actor/account and seller.
 * Token exchange happens AFTER consuming state, outside a DB transaction.
 *
 * No endpoint here makes an SP-API call or declares a Seller VERIFIED.
 */
public final class SellerOauthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SellerTokenCipher cipher;
    private final LwaAuthorizationCodeClient lwa;
    private final Clock clock;
    private final String appId, redirectUri;
    private final Set<String> allowedAmazonOrigins;

    public SellerOauthService(JdbcTemplate jdbc, TransactionTemplate tx,
                              SellerTokenCipher cipher, LwaAuthorizationCodeClient lwa,
                              Clock clock, String appId, String redirectUri,
                              Set<String> allowedAmazonOrigins) {
        if (appId == null || !appId.matches("amzn1\\.sellerapps\\.app\\.[A-Za-z0-9-]{10,128}"))
            throw new IllegalArgumentException("Invalid Amazon application ID");
        URI redirect = URI.create(redirectUri);
        if (!"https".equals(redirect.getScheme()) || redirect.getHost() == null
            || redirect.getUserInfo() != null || redirect.getFragment() != null)
            throw new IllegalArgumentException("Registered OAuth redirect must use HTTPS");
        if (allowedAmazonOrigins.isEmpty())
            throw new IllegalArgumentException("Amazon callback origins must be explicitly configured");
        this.jdbc=jdbc; this.tx=tx; this.cipher=cipher; this.lwa=lwa;
        this.clock=clock; this.appId=appId; this.redirectUri=redirectUri;
        this.allowedAmazonOrigins=Set.copyOf(allowedAmazonOrigins);
    }

    public record Start(String state, String authorizationUrl, String expiresAt) {}
    public record Connection(String status, String sellerId, String region) {}
    private record Claimed(UUID tenant, UUID actor, UUID account, String sellerId) {}

    public Start start(UUID tenant, UUID actor, UUID account, boolean beta) {
        String region = tx.execute(status -> jdbc.query("""
            SELECT region FROM seller_account
            WHERE tenant_id = ? AND id = ? AND connection_status = 'UNCONFIGURED'
              AND seller_id LIKE 'LOCAL-%'
              AND EXISTS (
                SELECT 1 FROM membership m JOIN app_user u ON u.id = m.user_id
                JOIN tenant t ON t.id = m.tenant_id
                WHERE m.tenant_id = ? AND m.user_id = ? AND m.status='ACTIVE'
                  AND u.status='ACTIVE' AND t.status='ACTIVE'
                  AND EXISTS (SELECT 1 FROM role_binding rb WHERE rb.tenant_id=m.tenant_id
                    AND rb.user_id=m.user_id AND rb.role_code='OWNER'
                    AND rb.seller_account_id IS NULL))
            """, (rs,n) -> rs.getString("region"), tenant, account, tenant, actor)
            .stream().findFirst().orElse(null));
        if (region == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String state = randomState();
        String digest = stateDigest(state);
        Instant now = clock.instant(), expires = now.plus(STATE_TTL);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO spapi_oauth_attempt
                (state_hash,tenant_id,actor_user_id,seller_account_id,created_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, digest, tenant, actor, account, java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(expires));
            audit(tenant, actor, account, "STARTED");
        });
        String sellerCentral = switch(region) {
            case "NA" -> "https://sellercentral.amazon.com";
            case "EU" -> "https://sellercentral.amazon.de";
            case "FE" -> "https://sellercentral.amazon.co.jp";
            default -> throw new IllegalStateException("Unknown registered region");
        };
        String url = sellerCentral + "/apps/authorize/consent?application_id=" + encode(appId)
            + "&state=" + encode(state) + (beta ? "&version=beta" : "");
        return new Start(state, url, expires.toString());
    }

    public URI handoff(String cookieState, String amazonCallbackUri,
                       String amazonState, String sellerId, String version) {
        validState(cookieState);
        validSeller(sellerId);
        if (amazonState == null || !amazonState.matches("[A-Za-z0-9._~|\\-]{8,512}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Amazon state");
        if (version != null && !version.equals("beta"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid draft version");
        URI callback = verifiedCallback(amazonCallbackUri, allowedAmazonOrigins);
        String digest = stateDigest(cookieState);
        int changed = tx.execute(s -> {
            int count = jdbc.update("""
                UPDATE spapi_oauth_attempt a SET expected_seller_id=?,status='HANDOFF'
                WHERE a.state_hash=? AND a.status='STARTED'
                  AND a.expires_at > now() AND a.created_at > now() - interval '10 minutes'
                  AND EXISTS (
                    SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id
                    JOIN tenant t ON t.id=m.tenant_id WHERE m.tenant_id=a.tenant_id
                      AND m.user_id=a.actor_user_id AND m.status='ACTIVE'
                      AND u.status='ACTIVE' AND t.status='ACTIVE'
                      AND EXISTS(SELECT 1 FROM role_binding rb
                        WHERE rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                          AND rb.role_code='OWNER' AND rb.seller_account_id IS NULL))
                """, sellerId, digest);
            if (count == 1) jdbc.update("""
                INSERT INTO spapi_oauth_audit
                (id,tenant_id,actor_user_id,seller_account_id,event_type)
                SELECT ?,tenant_id,actor_user_id,seller_account_id,'HANDOFF'
                FROM spapi_oauth_attempt WHERE state_hash=?
                """, UUID.randomUUID(), digest);
            return count;
        });
        if (changed != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state invalid or expired");
        String url = callback.toString() + "?amazon_state=" + encode(amazonState)
            + "&state=" + encode(cookieState) + "&redirect_uri=" + encode(redirectUri)
            + (version == null ? "" : "&version=beta");
        return URI.create(url);
    }

    /**
     * First transaction irrevocably consumes the state. An exchange error
     * requires a fresh authorization flow; it never stores incomplete grants.
     */
    public void complete(String cookieState, String returnedState,
                         String sellerId, String oauthCode) {
        validState(cookieState);
        validState(returnedState);
        if (!MessageDigest.isEqual(cookieState.getBytes(StandardCharsets.UTF_8),
                                   returnedState.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state mismatch");
        validSeller(sellerId);
        String digest = stateDigest(returnedState);
        Claimed claim = tx.execute(status -> {
            List<Claimed> found = jdbc.query("""
                UPDATE spapi_oauth_attempt a SET consumed_at=now(),status='CLAIMED'
                WHERE a.state_hash=? AND a.status='HANDOFF' AND a.consumed_at IS NULL
                  AND a.expires_at > now() AND a.expected_seller_id=?
                  AND EXISTS (
                    SELECT 1 FROM membership m JOIN tenant t ON t.id=m.tenant_id
                    JOIN app_user u ON u.id=m.user_id
                    WHERE m.tenant_id=a.tenant_id AND m.user_id=a.actor_user_id
                      AND m.status='ACTIVE' AND t.status='ACTIVE' AND u.status='ACTIVE'
                      AND EXISTS (SELECT 1 FROM role_binding rb
                        WHERE rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                        AND rb.role_code='OWNER' AND rb.seller_account_id IS NULL))
                RETURNING tenant_id,actor_user_id,seller_account_id,expected_seller_id
                """, (rs,n) -> new Claimed(
                    rs.getObject("tenant_id",UUID.class),
                    rs.getObject("actor_user_id",UUID.class),
                    rs.getObject("seller_account_id",UUID.class),
                    rs.getString("expected_seller_id")), digest, sellerId);
            return found.stream().findFirst().orElse(null);
        });
        if (claim == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth grant already used or expired");

        String refresh;
        try { refresh = lwa.exchange(oauthCode); }
        catch (Exception e) {
            tx.executeWithoutResult(s -> audit(claim.tenant(), claim.actor(),
                claim.account(), "EXCHANGE_FAILED"));
            // Never propagate code, response bodies or token exchange details.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Authorization exchange failed");
        }
        tx.executeWithoutResult(s -> {
            String region = jdbc.query("""
                SELECT region FROM seller_account
                WHERE tenant_id=? AND id=? AND connection_status='UNCONFIGURED'
                AND seller_id LIKE 'LOCAL-%'
                FOR UPDATE
                """, (rs,n)->rs.getString("region"), claim.tenant(),claim.account())
                .stream().findFirst().orElse(null);
            if (region == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Seller account state changed");
            var sealed = cipher.encrypt(claim.tenant(),claim.account(),claim.sellerId(),refresh);
            int written = jdbc.update("""
                INSERT INTO spapi_seller_authorization
                (id,tenant_id,seller_account_id,seller_id,region,status,ciphertext,nonce,key_version)
                VALUES (?,?,?,?,?,'PENDING_VERIFICATION',?,?,?)
                ON CONFLICT (tenant_id,seller_account_id) DO NOTHING
                """, UUID.randomUUID(),claim.tenant(),claim.account(),claim.sellerId(),
                region,sealed.ciphertext(),sealed.nonce(),sealed.version());
            if (written != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Seller authorization already exists");
            jdbc.update("""
                UPDATE seller_account SET connection_status='AUTHORIZATION_PENDING'
                WHERE tenant_id=? AND id=? AND connection_status='UNCONFIGURED'
                """, claim.tenant(),claim.account());
            audit(claim.tenant(),claim.actor(),claim.account(),"CODE_EXCHANGED");
        });
    }

    public Connection status(UUID tenant, UUID account) {
        return jdbc.query("""
            SELECT a.status,a.seller_id,a.region FROM spapi_seller_authorization a
            WHERE a.tenant_id=? AND a.seller_account_id=?
            """, (rs,n)->new Connection(rs.getString("status"),rs.getString("seller_id"),
                rs.getString("region")), tenant,account)
            .stream().findFirst().orElse(new Connection("NOT_CONNECTED",null,null));
    }

    public void revoke(UUID tenant, UUID actor, UUID account) {
        tx.executeWithoutResult(s -> {
            int updated=jdbc.update("""
                UPDATE spapi_seller_authorization
                SET status='REVOKED',ciphertext=NULL,nonce=NULL,
                    revoked_at=now(),updated_at=now()
                WHERE tenant_id=? AND seller_account_id=? AND status<>'REVOKED'
                """, tenant,account);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            jdbc.update("""
                UPDATE seller_account SET connection_status='REVOKED'
                WHERE tenant_id=? AND id=? AND connection_status='AUTHORIZATION_PENDING'
                """,tenant,account);
            audit(tenant,actor,account,"REVOKED");
        });
    }

    private void audit(UUID tenant, UUID actor, UUID account, String event) {
        jdbc.update("""
            INSERT INTO spapi_oauth_audit
            (id,tenant_id,actor_user_id,seller_account_id,event_type)
            VALUES (?,?,?,?,?)
            """, UUID.randomUUID(),tenant,actor,account,event);
    }

    public static URI verifiedCallback(String input, Set<String> origins) {
        URI value;
        try { value=URI.create(input); }
        catch(RuntimeException e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Amazon callback URI"); }
        String origin = value.getScheme()+"://"+value.getHost();
        if (!"https".equals(value.getScheme()) || value.getHost()==null
            || value.getUserInfo()!=null || value.getPort()!=-1
            || value.getFragment()!=null || value.getRawQuery()!=null
            || !value.getPath().startsWith("/apps/authorize/confirm/")
            || !origins.contains(origin)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Amazon callback not on allowlist");
        }
        return value;
    }

    private static void validSeller(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{5,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Seller identifier");
    }
    private static void validState(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{40,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"OAuth state missing or invalid");
    }
    private static String randomState() {
        byte[] bytes = new byte[48]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String stateDigest(String state) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String encode(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8);
    }
}
