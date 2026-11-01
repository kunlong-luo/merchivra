package org.merchivra.erp.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in continuation of explicit Owner-started Reports scans; no enrollment. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.spapi.reports",
    name={"enabled","scheduler-enabled"},havingValue="true")
public class SettlementReportScanScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(SettlementReportScanScheduler.class);
    private final SettlementReportScanService jobs;
    public SettlementReportScanScheduler(SettlementReportScanService jobs) {this.jobs=jobs;}

    @Scheduled(fixedDelayString="${merchivra.spapi.reports.scheduler-ms:120000}",
        initialDelayString="30000")
    public void poll() {
        for(var item:jobs.due(3)) {
            try {jobs.step(item[0],item[1]);}
            catch(RuntimeException e) {
                LOG.warn("Reports scan continuation failed: {}",e.getClass().getSimpleName());
            }
        }
    }
}
