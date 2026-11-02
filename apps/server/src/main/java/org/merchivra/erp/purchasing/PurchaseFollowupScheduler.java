package org.merchivra.erp.purchasing;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Disabled by default, no emails or supplier/API egress. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.purchase-followups",name="scheduler-enabled",
    havingValue="true")
public class PurchaseFollowupScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(PurchaseFollowupScheduler.class);
    private final JdbcTemplate jdbc;
    private final PurchaseFollowupService followups;
    public PurchaseFollowupScheduler(JdbcTemplate jdbc,PurchaseFollowupService followups) {
        this.jdbc=jdbc;this.followups=followups;
    }
    @Scheduled(fixedDelayString="$"+"{merchivra.purchase-followups.scan-interval-ms:900000}",
        initialDelayString="30000")
    public void scanActiveTenants() {
        for(UUID tenant:jdbc.queryForList(
            "SELECT id FROM tenant WHERE status='ACTIVE' ORDER BY id",UUID.class)) {
            try {
                followups.scan(tenant,null,"SCHEDULED");
            } catch(RuntimeException e) {
                LOG.warn("Local PO follow-up scan failed for tenant {}: {}",
                    tenant,e.getClass().getSimpleName());
            }
        }
    }
}
