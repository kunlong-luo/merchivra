package org.merchivra.erp.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in processing only of reports explicitly created by an Owner. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.finance.profit-report",
    name="scheduler-enabled",havingValue="true")
public class ProfitReportScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ProfitReportScheduler.class);
    private final ProfitReportJobService jobs;
    public ProfitReportScheduler(ProfitReportJobService jobs){this.jobs=jobs;}
    @Scheduled(fixedDelayString="${merchivra.finance.profit-report.scheduler-ms:15000}",
        initialDelayString="15000")
    public void tick(){
        for(var id:jobs.due(3)){
            try{jobs.step(id[0],id[1],id[2]);}
            catch(RuntimeException e){
                LOG.warn("V64 report batch continuation failed: {}",
                    e.getClass().getSimpleName());
            }
        }
    }
}
