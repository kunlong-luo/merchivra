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
}
