package org.merchivra.erp.catalogsync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in. Only progresses explicit Owner-started jobs; no Amazon write calls. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.catalog-sync",
    name={"enabled","scheduler-enabled"},havingValue="true")
public class CatalogSyncScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(CatalogSyncScheduler.class);
    private final CatalogSyncService service;
    public CatalogSyncScheduler(CatalogSyncService service){this.service=service;}

    @Scheduled(fixedDelayString="${merchivra.catalog-sync.scheduler-ms:10000}",
        initialDelayString="15000")
    public void poll() {
        for(var job:service.due(5)) {
            try { service.step(job.tenant(),job.marketplace(),job.stream()); }
            catch(RuntimeException e) {
                // Never log SP-API payload, pagination token, Seller or refresh token.
                LOG.warn("Read-only catalog sync page not completed: {}",
                    e.getClass().getSimpleName());
            }
        }
    }
}
