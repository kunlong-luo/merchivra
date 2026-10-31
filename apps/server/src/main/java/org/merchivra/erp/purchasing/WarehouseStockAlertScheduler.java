package org.merchivra.erp.purchasing;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Optional local-only periodic checks, disabled by default.
 * Single-tenant row lock in scan serializes multiple replicas; no Amazon calls.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.warehouse-alerts",name="scheduler-enabled",
    havingValue="true")
public class WarehouseStockAlertScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(WarehouseStockAlertScheduler.class);
    private final JdbcTemplate jdbc;
    private final WarehouseStockAlertService service;
    public WarehouseStockAlertScheduler(JdbcTemplate jdbc,WarehouseStockAlertService service) {
        this.jdbc=jdbc;
        this.service=service;
    }
    @Scheduled(fixedDelayString="${merchivra.warehouse-alerts.scan-interval-ms:900000}",
        initialDelayString="30000")
    public void scanActiveTenants() {
        for(UUID tenant:jdbc.queryForList(
            "SELECT id FROM tenant WHERE status='ACTIVE' ORDER BY id",UUID.class)) {
            try {
                service.scan(tenant,null,"SCHEDULED");
            } catch(RuntimeException e) {
                LOG.warn("Local warehouse scan failed for tenant {}: {}",
                    tenant,e.getClass().getSimpleName());
            }
        }
    }
}
