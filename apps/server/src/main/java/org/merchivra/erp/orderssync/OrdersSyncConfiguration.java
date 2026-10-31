package org.merchivra.erp.orderssync;

import org.merchivra.erp.demo.MockSpApiClient;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11C mutually exclusive source selection. Default is fully disabled.
 * LIVE must be an explicit deployment decision AND V12 Orders role evidence
 * must be valid for each account; a generic SP-API enabled flag is insufficient.
 */
@Configuration
@ConditionalOnProperty(prefix="merchivra.orders-sync",name="enabled",havingValue="true")
public class OrdersSyncConfiguration {
    @Bean
    OrdersSyncSource ordersSyncSource(
        MockSpApiClient mock, JdbcTemplate jdbc,
        ObjectProvider<SpApiSellerGrantVault> vaultProvider,
        ObjectProvider<SpApiOfficialSdkFactory> sdkProvider,
        @Value("${merchivra.orders-sync.mode:MOCK}") String mode,
        @Value("${merchivra.orders-sync.live-approved:false}") boolean liveApproved,
        @Value("${merchivra.demo.enabled:false}") boolean demoEnabled,
        @Value("${merchivra.spapi.enabled:false}") boolean outboundEnabled,
        @Value("${merchivra.spapi.sandbox:true}") boolean sandbox,
        @Value("${merchivra.spapi.allow-writes:false}") boolean allowWrites,
        @Value("${merchivra.spapi.oauth.enabled:false}") boolean oauthEnabled,
        @Value("${merchivra.spapi.private.enabled:false}") boolean privateEnabled,
        @Value("${merchivra.spapi.verification.enabled:false}") boolean verificationEnabled) {

        if("MOCK".equals(mode)) {
            if(!demoEnabled || outboundEnabled || liveApproved) {
                throw new IllegalStateException(
                    "MOCK Orders Worker requires Demo-only, outbound disabled, and live approval off");
            }
            return new MockOrdersSyncSource(mock,true);
        }

        if(!"LIVE".equals(mode) || !liveApproved || demoEnabled || !outboundEnabled
           || sandbox || allowWrites || (!oauthEnabled && !privateEnabled) || !verificationEnabled) {
            throw new IllegalStateException(
                "LIVE Orders requires explicit approval, production read-only SP-API, verified OAuth and no Demo");
        }
        var vault=vaultProvider.getIfAvailable();
        var factory=sdkProvider.getIfAvailable();
        if(vault==null || factory==null)
            throw new IllegalStateException("LIVE Orders requires audited vault and official SDK");
        var gateway=new ApprovedOrdersReadGateway(jdbc,vault,
            new TypedOrdersSdkReader(factory));
        return new LiveOrdersSyncSource(gateway);
    }
}
