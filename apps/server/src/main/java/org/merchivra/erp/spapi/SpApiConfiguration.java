package org.merchivra.erp.spapi;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production integration is opt-in. No HTTP controller or token database exists.
 * Even with this bean enabled, a future trusted account Worker must provide a
 * per-seller approved refresh token from protected storage before any call.
 */
@Configuration
@ConditionalOnProperty(prefix="merchivra.spapi", name="enabled", havingValue="true")
public class SpApiConfiguration {
    @Bean
    SpApiOfficialSdkFactory spApiOfficialSdkFactory(
        @Value("${merchivra.spapi.lwa-client-id:}") String clientId,
        @Value("${merchivra.spapi.lwa-client-secret:}") String clientSecret,
        @Value("${merchivra.spapi.sandbox:true}") boolean sandbox) {
        return new SpApiOfficialSdkFactory(clientId, clientSecret, true, sandbox);
    }

    @Bean
    @ConditionalOnProperty(prefix="merchivra.spapi.oauth", name="enabled", havingValue="true")
    SpApiSellerGrantVault spApiSellerGrantVault(
        org.springframework.jdbc.core.JdbcTemplate jdbc,
        @Value("${merchivra.spapi.oauth.encryption-key-base64:}") String key) {
        return new SpApiSellerGrantVault(jdbc,new SellerTokenCipher(key,1));
    }

    @Bean
    @ConditionalOnProperty(prefix="merchivra.spapi.oauth", name="enabled", havingValue="true")
    SellerOauthService sellerOauthService(
        org.springframework.jdbc.core.JdbcTemplate jdbc,
        org.springframework.transaction.support.TransactionTemplate transactions,
        @Value("${merchivra.spapi.oauth.encryption-key-base64:}") String key,
        @Value("${merchivra.spapi.oauth.application-id:}") String appId,
        @Value("${merchivra.spapi.oauth.redirect-uri:}") String redirectUri,
        @Value("${merchivra.spapi.oauth.allowed-amazon-origins:}") String origins,
        @Value("${merchivra.spapi.lwa-client-id:}") String clientId,
        @Value("${merchivra.spapi.lwa-client-secret:}") String clientSecret,
        @Value("${merchivra.demo.enabled:false}") boolean demoEnabled) {
        if (demoEnabled) throw new IllegalStateException(
            "Real Seller OAuth cannot be enabled together with synthetic Demo mode");
        var cipher = new SellerTokenCipher(key, 1);
        var exchanger = LwaAuthorizationCodeClient.official(clientId,clientSecret,redirectUri);
        var allowed = java.util.Arrays.stream(origins.split(","))
            .map(String::trim).filter(v -> !v.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new SellerOauthService(jdbc,transactions,cipher,exchanger,
            java.time.Clock.systemUTC(),appId,redirectUri,allowed);
    }

    @Bean
    SpApiClient spApiClient(
        @Value("${merchivra.spapi.lwa-client-id:}") String clientId,
        @Value("${merchivra.spapi.lwa-client-secret:}") String clientSecret,
        @Value("${merchivra.spapi.sandbox:true}") boolean sandbox,
        @Value("${merchivra.spapi.allow-writes:false}") boolean allowWrites) {
        var http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return SpApiClient.official(http,
            LwaTokenClient.official(http, clientId, clientSecret),
            sandbox, true, allowWrites);
    }

    @Bean
    @ConditionalOnProperty(prefix="merchivra.spapi.verification", name="enabled", havingValue="true")
    SellerMarketplaceGateway sellerMarketplaceGateway(
        SpApiOfficialSdkFactory sdk,
        @Value("${merchivra.spapi.sandbox:true}") boolean sandbox,
        @Value("${merchivra.spapi.oauth.enabled:false}") boolean oauthEnabled,
        @Value("${merchivra.demo.enabled:false}") boolean demo) {
        if (sandbox || !oauthEnabled || demo)
            throw new IllegalStateException(
                "Live Seller verification requires production endpoint, OAuth and Demo disabled");
        return new OfficialSellerMarketplaceGateway(sdk);
    }

    @Bean
    @ConditionalOnProperty(prefix="merchivra.spapi.verification", name="enabled", havingValue="true")
    SellerConnectionVerificationService sellerConnectionVerificationService(
        org.springframework.jdbc.core.JdbcTemplate jdbc,
        org.springframework.transaction.support.TransactionTemplate transactions,
        SellerMarketplaceGateway gateway,
        @Value("${merchivra.spapi.oauth.encryption-key-base64:}") String key) {
        return new SellerConnectionVerificationService(jdbc,transactions,
            new SellerTokenCipher(key,1),gateway,java.time.Clock.systemUTC());
    }
}
