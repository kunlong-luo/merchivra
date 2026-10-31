package org.merchivra.erp.purchasing;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Only advisory local supplier shipment scans. Disabled unless explicitly configured. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="merchivra.supplier-asns",name="scheduler-enabled",
    havingValue="true")
public class SupplierAsnScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(SupplierAsnScheduler.class);
    private final JdbcTemplate jdbc;
    private final SupplierAsnService asns;
    public SupplierAsnScheduler(JdbcTemplate jdbc,SupplierAsnService asns) {
        this.jdbc=jdbc;this.asns=asns;
    }
    @Scheduled(fixedDelayString="${merchivra.supplier-asns.scan-interval-ms:900000}",
        initialDelayString="30000")
    public void scanTenants() {
        for(UUID tenant:jdbc.queryForList(
            "SELECT id FROM tenant WHERE status='ACTIVE' ORDER BY id",UUID.class)) {
            try {
                asns.scan(tenant,null,"SCHEDULED");
            } catch(RuntimeException e) {
                LOG.warn("Local supplier ASN scan failed for tenant {}: {}",
                    tenant,e.getClass().getSimpleName());
            }
        }
    }
}
