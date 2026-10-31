package org.merchivra.erp.operations;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Disabled by default. Advisory scans only; no live SP-API calls.
 * Multiple nodes are safe: scan locks tenant row, findings are unique.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "merchivra.operations", name = "scheduler-enabled",
                       havingValue = "true")
public class OperationsScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(OperationsScheduler.class);
    private final JdbcTemplate jdbc;
    private final OperationsAutomationService service;

    public OperationsScheduler(JdbcTemplate jdbc, OperationsAutomationService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${merchivra.operations.scan-interval-ms:900000}",
               initialDelayString = "30000")
    public void scanActiveTenants() {
        for (UUID tenant : jdbc.queryForList(
                "SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id",
                UUID.class)) {
            try {
                service.scan(tenant, null, "SCHEDULED");
            } catch (RuntimeException exception) {
                // Avoid leaking SKU/order content or credentials into logs.
                LOG.warn("Advisory operations scan failed for tenant {}", tenant,
                         exception.getClass().getSimpleName());
            }
        }
    }
}
