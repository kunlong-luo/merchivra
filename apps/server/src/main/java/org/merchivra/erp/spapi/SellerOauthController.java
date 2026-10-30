package org.merchivra.erp.spapi;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Public GET endpoints only for Amazon redirect handoffs, protected by
 * one-time state AND short-lived Lax HttpOnly cookie. No browser-supplied
 * tenant ID, Seller secret, refresh token or arbitrary redirect URL accepted.
 */
@RestController
@ConditionalOnProperty(prefix="merchivra.spapi.oauth",name="enabled",havingValue="true")
@RequestMapping("/api/v1")
public class SellerOauthController {
    private static final String HANDOFF_COOKIE = "MERCHIVRA_SPAPI_HANDOFF";
    private final TenantAccessService access;
    private final SellerOauthService oauth;
    private final boolean secureCookie;

    public SellerOauthController(TenantAccessService access, SellerOauthService oauth,
        @Value("${merchivra.spapi.oauth.secure-cookie:true}") boolean secureCookie) {
        this.access=access; this.oauth=oauth; this.secureCookie=secureCookie;
    }
    public record StartLink(String authorizationUrl, String expiresAt) {}

    @PostMapping("/seller-accounts/{accountId}/oauth/start")
    public ResponseEntity<StartLink> start(Authentication auth, HttpServletRequest req,
        @PathVariable UUID accountId, @RequestParam(defaultValue="false") boolean beta) {
        var start=oauth.start(access.currentTenant(auth,req),
            access.authenticatedUser(auth),accountId,beta);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE,cookie(start.state(),Duration.ofMinutes(10)))
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("Referrer-Policy","no-referrer")
            .body(new StartLink(start.authorizationUrl(),start.expiresAt()));
    }

    /** Amazon's first redirect: login URI with callback_uri/amazon_state/Seller ID. */
    @GetMapping("/spapi/oauth/login")
    public ResponseEntity<Void> login(
        @CookieValue(value=HANDOFF_COOKIE,required=false) String state,
        @RequestParam("amazon_callback_uri") String callback,
        @RequestParam("amazon_state") String amazonState,
        @RequestParam("selling_partner_id") String sellerId,
        @RequestParam(value="version",required=false) String version) {
        URI target=oauth.handoff(state,callback,amazonState,sellerId,version);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(target)
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("Referrer-Policy","no-referrer")
            .build();
    }

    /** Amazon's final redirect: code and grant are consumed before any write. */
    @GetMapping("/spapi/oauth/callback")
    public ResponseEntity<Void> callback(
        @CookieValue(value=HANDOFF_COOKIE,required=false) String cookieState,
        @RequestParam("state") String state,
        @RequestParam("selling_partner_id") String sellerId,
        @RequestParam("spapi_oauth_code") String code) {
        oauth.complete(cookieState,state,sellerId,code);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create("/?amazon_oauth=pending_verification"))
            .header(HttpHeaders.SET_COOKIE,cookie("",Duration.ZERO))
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("Referrer-Policy","no-referrer")
            .build();
    }

    @GetMapping("/seller-accounts/{accountId}/oauth/status")
    public SellerOauthService.Connection status(Authentication auth,HttpServletRequest req,
        @PathVariable UUID accountId) {
        return oauth.status(access.currentTenant(auth,req),accountId);
    }
    @PostMapping("/seller-accounts/{accountId}/oauth/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth,HttpServletRequest req,
        @PathVariable UUID accountId) {
        oauth.revoke(access.currentTenant(auth,req),access.authenticatedUser(auth),accountId);
    }

    private String cookie(String state, Duration age) {
        return ResponseCookie.from(HANDOFF_COOKIE,state)
            .path("/api/v1/spapi/oauth").maxAge(age)
            .httpOnly(true).secure(secureCookie).sameSite("Lax").build().toString();
    }
}
