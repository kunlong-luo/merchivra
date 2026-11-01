package org.merchivra.erp.spapi;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpApiRetryPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void parsesDeltaSecondsAndHttpDateWithoutTrustingInvalidInputs() {
        assertEquals(Duration.ofSeconds(30), SpApiRetryPolicy.retryAfter("30", NOW));
        assertEquals(Duration.ofSeconds(60),
            SpApiRetryPolicy.retryAfter("Sun, 01 Nov 2026 00:01:00 GMT", NOW));
        assertEquals(Duration.ZERO, SpApiRetryPolicy.retryAfter("-9", NOW));
        assertEquals(Duration.ZERO, SpApiRetryPolicy.retryAfter("not-a-date", NOW));
        assertEquals(Duration.ZERO, SpApiRetryPolicy.retryAfter((String) null, NOW));
        assertEquals(Duration.ofHours(1), SpApiRetryPolicy.retryAfter("999999", NOW));
    }

    @Test
    void exponentialJitterIsBoundedAndHonorsAmazonMinimum() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            Duration delay = SpApiRetryPolicy.nextDelay(attempt, Duration.ZERO);
            long base = Math.min(3_600_000, 5_000L * (1L << (attempt - 1)));
            assertTrue(delay.toMillis() >= base);
            assertTrue(delay.compareTo(SpApiRetryPolicy.MAX_DELAY) <= 0);
        }
        for (int i = 0; i < 5; i++) {
            assertTrue(SpApiRetryPolicy.nextDelay(1, Duration.ofSeconds(45))
                .compareTo(Duration.ofSeconds(45)) >= 0);
        }
        assertEquals(Duration.ofHours(1),
            SpApiRetryPolicy.nextDelay(12, Duration.ofHours(3)));
        assertThrows(IllegalArgumentException.class,
            () -> SpApiRetryPolicy.nextDelay(0, Duration.ZERO));
    }
}
