package org.merchivra.erp.demo;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoSyncServiceTest {
    private final DemoRepository repository = mock(DemoRepository.class);
    private final DemoSyncService sync =
        new DemoSyncService(repository, new MockMarketplaceOverviewGateway());

    private static final String KEY = "demo-test-replay-01";

    @Test
    void startupOnlySeedsIfSnapshotMissing() {
        sync.initialize();
        verify(repository).seedIfAbsent(any(DemoModels.Overview.class));
    }

    @Test
    void successfulRunWritesOnlySyntheticSnapshot() {
        when(repository.insertRun(any(UUID.class), eq(KEY), eq("RESEED:OK"),
            eq("SUCCEEDED"), eq(11), isNull())).thenReturn(true);
        var result = new DemoSyncRun(UUID.randomUUID().toString(), "SUCCEEDED", "MOCK", 11,
            null, "2026-10-30T00:00:00Z", "2026-10-30T00:00:00Z");
        when(repository.findRun(any(UUID.class))).thenReturn(result);
        assertEquals("SUCCEEDED", sync.synchronize(KEY, false).status());
        verify(repository).replaceOverview(argThat(o -> "MOCK".equals(o.mode())));
    }

    @Test
    void failedSimulationRecordsStatusWithoutChangingSnapshot() {
        when(repository.insertRun(any(UUID.class), eq(KEY), eq("RESEED:FAIL"),
            eq("FAILED_RETRYABLE"), eq(0), eq("MOCK_SIMULATED_FAILURE"))).thenReturn(true);
        var result = new DemoSyncRun(UUID.randomUUID().toString(), "FAILED_RETRYABLE",
            "MOCK", 0, "MOCK_SIMULATED_FAILURE",
            "2026-10-30T00:00:00Z", "2026-10-30T00:00:00Z");
        when(repository.findRun(any(UUID.class))).thenReturn(result);
        assertTrue(sync.synchronize(KEY, true).retryable());
        verify(repository, never()).replaceOverview(any());
    }

    @Test
    void sameIdempotencyKeyDoesNotWriteSnapshotTwice() {
        var existing = new DemoSyncRun(UUID.randomUUID().toString(), "SUCCEEDED",
            "MOCK", 11, null, "2026-10-30T00:00:00Z", "2026-10-30T00:00:00Z");
        when(repository.findByIdempotencyKey(KEY)).thenReturn(existing);
        when(repository.matchesRequest(KEY, "RESEED:OK")).thenReturn(true);

        assertSame(existing, sync.synchronize(KEY, false));
        verify(repository, never()).replaceOverview(any());
    }

    @Test
    void reusingKeyWithDifferentParametersReturnsConflict() {
        var existing = new DemoSyncRun(UUID.randomUUID().toString(), "FAILED_RETRYABLE",
            "MOCK", 0, "MOCK_SIMULATED_FAILURE",
            "2026-10-30T00:00:00Z", "2026-10-30T00:00:00Z");
        when(repository.findByIdempotencyKey(KEY)).thenReturn(existing);
        when(repository.matchesRequest(KEY, "RESEED:OK")).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> sync.synchronize(KEY, false));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(repository, never()).replaceOverview(any());
    }

    @Test
    void malformedKeyIsRejectedBeforeDatabaseWrite() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> sync.synchronize("bad!", false));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(repository);
    }
}
