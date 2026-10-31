package org.merchivra.erp.purchasing;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Optional tenant-isolated local QC alerts; disabled by default. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.purchase-quality",name="scheduler-enabled",
    havingValue="true")
public class PurchaseQualityScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(PurchaseQualityScheduler.class);
    private final JdbcTemplate jdbc;
    private final PurchaseQualityService quality;
    public PurchaseQualityScheduler(JdbcTemplate jdbc,PurchaseQualityService quality) {
        this.jdbc=jdbc;this.quality=quality;
    }
    @Scheduled(fixedDelayString="${merchivra.purchase-quality.scan-interval-ms:900000}",
        initialDelayString="30000")
    public void scanActiveTenants() {
        for(UUID tenant:jdbc.queryForList(
            "SELECT id FROM tenant WHERE status='ACTIVE' ORDER BY id",UUID.class)) {
            try {
                quality.scan(tenant,null,"SCHEDULED");
            }catch(RuntimeException e){
                LOG.warn("Local quality scan failed for tenant {}: {}",
                    tenant,e.getClass().getSimpleName());
            }
        }
    }
}
