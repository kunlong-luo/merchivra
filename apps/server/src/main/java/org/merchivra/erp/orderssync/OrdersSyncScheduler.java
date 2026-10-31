package org.merchivra.erp.orderssync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Optional one-page-per-due-job scheduler. Every step validates tenant/LOCAL
 * seller eligibility, and the lease prevents duplicate concurrent workers.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.orders-sync",name="scheduler-enabled",havingValue="true")
public class OrdersSyncScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(OrdersSyncScheduler.class);
    private final OrdersSyncService service;
    public OrdersSyncScheduler(OrdersSyncService service) { this.service=service; }

    @Scheduled(fixedDelayString="${merchivra.orders-sync.scheduler-ms:30000}",
               initialDelayString="10000")
    public void poll() {
        for(var job:service.due(10)) {
            try { service.step(job[0],job[1]); }
            catch(RuntimeException ex) {
                // Do not leak cursor or source JSON.
                LOG.warn("Orders sync step not completed: {}",ex.getClass().getSimpleName());
            }
        }
    }
}
