package org.merchivra.erp.demo;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/sync-runs")
@ConditionalOnProperty(prefix = "merchivra.demo", name = "enabled", havingValue = "true")
public class DemoSyncController {
    private final DemoSyncService sync;

    public DemoSyncController(DemoSyncService sync) { this.sync = sync; }

    public record Request(boolean simulateFailure) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DemoSyncRun start(@RequestHeader("Idempotency-Key") String key,
                             @RequestBody(required = false) Request request) {
        return sync.synchronize(key, request != null && request.simulateFailure());
    }

    @GetMapping
    public List<DemoSyncRun> recent() { return sync.recentRuns(); }

    @GetMapping("/{id}")
    public DemoSyncRun get(@PathVariable UUID id) { return sync.run(id); }
}
