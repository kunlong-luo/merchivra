package org.merchivra.erp.demo;

import java.util.List;
import java.util.UUID;
import org.merchivra.erp.demo.DemoModels.Overview;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in local demonstration: never reads live Amazon data.
 * No background worker and no automatic retry at this stage.
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "merchivra.demo", name = "enabled", havingValue = "true")
public class DemoSyncService implements MarketplaceOverviewGateway {
    private final DemoRepository repository;
    private final MockMarketplaceOverviewGateway mock;

    public DemoSyncService(DemoRepository repository, MockMarketplaceOverviewGateway mock) {
        this.repository = repository;
        this.mock = mock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        // INSERT IF ABSENT: restarts must preserve the previously persisted snapshot.
        repository.seedIfAbsent(mock.getOverview());
    }

    @Override
    public Overview getOverview() {
        return repository.readOverview();
    }

    @Transactional
    public DemoSyncRun synchronize(String idempotencyKey, boolean simulateFailure) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        String fingerprint = simulateFailure ? "RESEED:FAIL" : "RESEED:OK";
        Overview fixture = mock.getOverview();
        UUID id = UUID.randomUUID();
        String status = simulateFailure ? "FAILED_RETRYABLE" : "SUCCEEDED";
        String failure = simulateFailure ? "MOCK_SIMULATED_FAILURE" : null;
        int count = simulateFailure ? 0
            : fixture.accounts().size() + fixture.listings().size()
            + fixture.orders().size() + fixture.inventory().size();

        boolean inserted = repository.insertRun(id, idempotencyKey, fingerprint, status, count, failure);
        if (!inserted) {
            DemoSyncRun previous = repository.findByIdempotencyKey(idempotencyKey);
            if (previous == null) throw new IllegalStateException("Idempotency record missing");
            // A key cannot be silently reused with a different request body.
            if (!repository.matchesRequest(idempotencyKey, fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key reused with different request");
            }
            return previous;
        }
        if (!simulateFailure) repository.replaceOverview(fixture);
        // Same DB transaction: a failed snapshot write must not produce SUCCEEDED.
        return repository.findRun(id);
    }

    public List<DemoSyncRun> recentRuns() { return repository.recentRuns(); }

    public DemoSyncRun run(UUID id) {
        DemoSyncRun result = repository.findRun(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo run not found");
        return result;
    }
}
