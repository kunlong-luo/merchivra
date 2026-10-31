package org.merchivra.erp.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in Finances page continuation; no automatic Seller enrollment or Amazon writes. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.finances-sync",
  name={"enabled","scheduler-enabled"},havingValue="true")
public class FinancesSyncScheduler {
 private static final Logger LOG=LoggerFactory.getLogger(FinancesSyncScheduler.class);
 private final FinancesSyncService service;
 public FinancesSyncScheduler(FinancesSyncService service){this.service=service;}
 @Scheduled(fixedDelayString="${merchivra.finances-sync.scheduler-ms:60000}",
  initialDelayString="15000")
 public void poll() {
  for(var job:service.due(5)) {
   try{service.step(job[0],job[1]);}
   catch(RuntimeException e) {
    LOG.warn("Finances page not completed: {}",e.getClass().getSimpleName());
   }
  }
 }
}
