package org.merchivra.erp.finance;

import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V222 CI-ONLY test seam: freeze a REAL authenticated HTTP request strictly
 * BETWEEN two V216 independent repeatable-read snapshots. This class is not
 * registered without both a CI-only Spring profile and explicit enablement.
 * No financial mutation, no test-only HTTP endpoint, no public API surface.
 */
@Component
@Profile("ci-v222")
@ConditionalOnProperty(name="merchivra.ci.v222.read-boundary",havingValue="true")
public final class ReceiptCostIntegrityCiBoundaryV222 {
    private static final UUID RECEIPT=UUID.fromString(
        "00000000-0000-4000-8000-0000000056b9"); // 22201
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReceiptCostIntegrityCiBoundaryV222(JdbcTemplate jdbc,
        PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        tx=new TransactionTemplate(manager);
        tx.setReadOnly(true);
        tx.setTimeout(12);
    }

    @PostConstruct
    void rejectAnyNonDisposableEnvironment() {
        for(var entry:java.util.Map.of(
            "GITHUB_ACTIONS","true",
            "GITHUB_REPOSITORY","kunlong-luo/merchivra",
            "GITHUB_REF","refs/heads/main",
            "MERCHIVRA_V222_DISPOSABLE_ONLY","true",
            "DB_HOST","127.0.0.1",
            "DB_PORT","5432",
            "DB_NAME","merchivra",
            "DB_USER","merchivra",
            "MERCHIVRA_SPAPI_ENABLED","false",
            "MERCHIVRA_SPAPI_OAUTH_ENABLED","false"
        ).entrySet()) {
            if(!entry.getValue().equals(System.getenv(entry.getKey())))
                throw new IllegalStateException(
                    "V222 read boundary is permitted ONLY in disposable main CI");
        }
        if(!"ci_disposable_database_password".equals(System.getenv("DB_PASSWORD"))
          || !"v65-ci-owner".equals(System.getenv("MERCHIVRA_BOOTSTRAP_USERNAME"))
          || !"ci-disposable-v65-only-secret-0000".equals(
              System.getenv("MERCHIVRA_BOOTSTRAP_PASSWORD")))
            throw new IllegalStateException("V222 CI-only database identity mismatch");
    }

    void afterFirstSnapshot(UUID receipt) {
        if(!RECEIPT.equals(receipt))return;
        // This runs AFTER the first RR COMMIT and BEFORE the second RR BEGIN.
        // An external disposable PG18 session holds advisory key (222,2),
        // making the real HTTP worker observable as an ungranted lock waiter.
        // The worker proceeds only once the test releases the same PG lock.
        // xact-level lock cannot leak into a pooled JDBC connection.
        tx.executeWithoutResult(ignored ->
            jdbc.execute("SELECT pg_advisory_xact_lock(222,2)"));
    }
}
