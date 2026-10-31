package org.merchivra.erp.orderssync;

import org.merchivra.erp.demo.MockSpApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V11A is an opt-in, local-only worker. A separate, role-verified production
 * source will be necessary before any real Seller Orders traffic is enabled.
 */
@Configuration
@ConditionalOnProperty(prefix="merchivra.orders-sync", name="enabled", havingValue="true")
public class OrdersSyncConfiguration {
    @Bean
    OrdersSyncSource ordersSyncSource(MockSpApiClient mock,
        @Value("${merchivra.demo.enabled:false}") boolean demoEnabled,
        @Value("${merchivra.spapi.enabled:false}") boolean outboundEnabled) {
        if (!demoEnabled || outboundEnabled) {
            throw new IllegalStateException(
                "V11A Orders Worker is fixture-only: Demo required, Amazon outbound forbidden");
        }
        return new MockOrdersSyncSource(mock, true);
    }
}
