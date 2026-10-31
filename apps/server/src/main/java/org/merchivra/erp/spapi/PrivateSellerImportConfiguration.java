package org.merchivra.erp.spapi;

import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Explicit one-shot maintenance command. Import secrets only through server
 * environment/secret manager, NEVER browser, command-line arguments or logs.
 * The process exits after import; it cannot become a normal web server with
 * the import secret still in its environment.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="merchivra.spapi.private-import",name="enabled",
                       havingValue="true")
public class PrivateSellerImportConfiguration {
    @Bean
    ApplicationRunner privateSellerOneShotImport(
        PrivateSellerGrantService service,ConfigurableApplicationContext application,
        @Value("${merchivra.demo.enabled:false}") boolean demo,
        @Value("${merchivra.spapi.private.enabled:false}") boolean privateMode,
        @Value("${merchivra.spapi.oauth.enabled:false}") boolean publicOauth,
        @Value("${merchivra.spapi.private-import.tenant-id:}") String tenantId,
        @Value("${merchivra.spapi.private-import.account-id:}") String accountId,
        @Value("${merchivra.spapi.private-import.seller-id:}") String sellerId,
        @Value("${merchivra.spapi.private-import.refresh-token:}") String refreshToken) {
        if(demo || !privateMode || publicOauth)
            throw new IllegalStateException(
                "One-shot private import requires private mode, no Demo, and public OAuth disabled");
        return args->{
            // Sanitize the exception: neither the token nor configuration values
            // are ever interpolated into the failure message.
            try {
                service.importFromServer(UUID.fromString(tenantId),
                    UUID.fromString(accountId),sellerId,refreshToken);
            } catch(RuntimeException e) {
                throw new IllegalStateException(
                    "Private Seller import rejected; review server-side import configuration");
            }
            int exit=SpringApplication.exit(application,()->0);
            System.exit(exit);
        };
    }
}
