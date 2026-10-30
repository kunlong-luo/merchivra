package org.merchivra.erp.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LocalAdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalAdminBootstrap.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    private final String username;
    private final String password;
    private final String tenantName;

    public LocalAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
        TransactionTemplate transactions,
        @Value("${merchivra.bootstrap.username:}") String username,
        @Value("${merchivra.bootstrap.password:}") String password,
        @Value("${merchivra.bootstrap.tenant-name:Merchivra Workspace}") String tenantName) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.transactions = transactions;
        this.username = username;
        this.password = password;
        this.tenantName = tenantName;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(tx -> {
            // Protect against two app instances bootstrapping concurrently.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(746289001)", Object.class);
            Long existing = jdbc.queryForObject("SELECT count(*) FROM local_credential", Long.class);
            if (existing != null && existing > 0) return;
            if (username.isBlank() && password.isBlank()) {
                log.warn("No local admin configured. Set both MERCHIVRA_BOOTSTRAP_USERNAME and MERCHIVRA_BOOTSTRAP_PASSWORD on first start.");
                return;
            }
            if (!username.matches("[A-Za-z0-9._-]{3,64}") || password.length() < 16
                || password.isBlank() || tenantName.isBlank() || tenantName.length() > 200) {
                throw new IllegalStateException("Bootstrap requires login 3-64 chars, nonempty tenant name and password >=16 chars");
            }
            UUID tenant = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            jdbc.update("INSERT INTO tenant(id, name) VALUES (?, ?)", tenant, tenantName);
            jdbc.update("INSERT INTO app_user(id, identity_ref) VALUES (?, ?)",
                user, "local:" + username);
            jdbc.update("INSERT INTO local_credential(user_id, login_name, password_hash) VALUES (?, ?, ?)",
                user, username, encoder.encode(password));
            jdbc.update("INSERT INTO membership(tenant_id, user_id) VALUES (?, ?)", tenant, user);
            jdbc.update("""
                INSERT INTO role_binding(id, tenant_id, user_id, role_code, seller_account_id)
                VALUES (?, ?, ?, 'OWNER', NULL)
                """, UUID.randomUUID(), tenant, user);
            log.info("Initial local OWNER created; remove bootstrap password from environment after first initialization.");
        });
    }
}
