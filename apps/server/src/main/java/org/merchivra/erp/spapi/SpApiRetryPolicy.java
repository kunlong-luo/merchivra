package org.merchivra.erp.spapi;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Shared bounded retry policy for durable workers; never sleeps on request threads. */
public final class SpApiRetryPolicy {
    public static final Duration MAX_DELAY = Duration.ofHours(1);
    private SpApiRetryPolicy() { }

    /** Retry-After may be seconds or an HTTP-date; malformed headers are ignored. */
    public static Duration retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return Duration.ZERO;
        try {
            long seconds = Long.parseLong(header.trim());
            return bounded(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                Instant then = ZonedDateTime.parse(header.trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return bounded(Duration.between(now, then));
            } catch (DateTimeParseException ignoredDate) {
                return Duration.ZERO;
            }
        }
    }

    /** SDK header maps may use any casing; ignore missing or empty metadata. */
    public static String headerValue(Map<String,List<String>> headers, String name) {
        if (headers == null || name == null) return null;
        for (var entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null
                && !entry.getValue().isEmpty()) return entry.getValue().getFirst();
        }
        return null;
    }

    public static Duration retryAfter(Map<String,List<String>> headers, Instant now) {
        return retryAfter(headerValue(headers, "Retry-After"), now);
    }

    /** Exponential backoff with positive jitter and any server-declared minimum. */
    public static Duration nextDelay(int attempt, Duration minimum) {
        if (attempt < 1) throw new IllegalArgumentException("Retry attempt must be >= 1");
        long baseMillis = Math.min(MAX_DELAY.toMillis(),
            5_000L * (1L << Math.min(attempt - 1, 9)));
        long wiggle = Math.max(1, baseMillis / 2);
        long jitter = ThreadLocalRandom.current().nextLong(wiggle);
        long delay = Math.max(baseMillis + jitter,
            bounded(minimum == null ? Duration.ZERO : minimum).toMillis());
        return Duration.ofMillis(Math.min(MAX_DELAY.toMillis(), delay));
    }

    public static Duration bounded(Duration duration) {
        if (duration == null || duration.isNegative()) return Duration.ZERO;
        return duration.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : duration;
    }
}
