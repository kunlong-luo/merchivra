package org.merchivra.erp.spapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomic Redis SET NX PX: conservatively admits one request per seller and
 * generated SDK family per cooldown interval, across all backend instances.
 * Fixed TTL is anchored in Redis, not application clocks; no token or Seller
 * ID is logged or saved in plaintext. Official SDK family admission remains
 * conservative; core operation calls use atomic Redis-time GCRA with no
 * assumed burst unless an operator explicitly approves capacity after learning
 * a usage-plan rate. A 429 fence and operation admission are checked together.
 *
 * Must be shared by every app node that uses the same Amazon application.
 * Redis errors FAIL CLOSED; callers must retry through durable SQL jobs.
 */
@Component
@ConditionalOnProperty(prefix = "merchivra.spapi.rate-limit", name = "enabled", havingValue = "true")
public class RedisSpApiRateLimiter implements SpApiRateLimiter {
    private static final Duration PLAN_TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> FENCE_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('PTTL', KEYS[1])
        local requested = tonumber(ARGV[1])
        if current < requested then
            redis.call('PSETEX', KEYS[1], requested, '1')
        end
        return 1
        """, Long.class);

    /** Atomic Redis-time GCRA. A positive return is milliseconds until eligible. */
    private static final DefaultRedisScript<Long> OPERATION_GCRA = new DefaultRedisScript<>("""
        local fence = redis.call('PTTL', KEYS[3])
        if fence > 0 then return fence end
        if fence == -1 then return 3600000 end
        local learned = tonumber(redis.call('GET', KEYS[2]))
        local interval = tonumber(ARGV[1])
        local burst = 1
        if learned and learned >= 250 and learned <= 3600000 then
            interval = learned
            burst = tonumber(ARGV[2])
        end
        local clock = redis.call('TIME')
        local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
        local stored = redis.call('GET', KEYS[1])
        local next = tonumber(stored) or now
        if stored and not tonumber(stored) then return 3600000 end
        local earliest = next - (burst - 1) * interval
        if now < earliest then return math.max(1, math.ceil(earliest - now)) end
        local updated = math.max(next, now) + interval
        local ttl = math.max(1000, math.ceil(updated - now + (burst - 1) * interval + 1000))
        redis.call('PSETEX', KEYS[1], ttl, tostring(updated))
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration cooldown;
    private final SpApiBurstPolicy bursts;
    private final MeterRegistry meters;

    public RedisSpApiRateLimiter(StringRedisTemplate redis, long millis) {
        this(redis, millis, "", null);
    }

    public RedisSpApiRateLimiter(StringRedisTemplate redis, long millis, String approvedBursts) {
        this(redis, millis, approvedBursts, null);
    }

    @Autowired
    public RedisSpApiRateLimiter(StringRedisTemplate redis,
        @Value("${merchivra.spapi.rate-limit.cooldown-ms:2000}") long millis,
        @Value("${merchivra.spapi.rate-limit.approved-bursts:}") String approvedBursts,
        MeterRegistry meters) {
        this.redis = Objects.requireNonNull(redis);
        if (millis < 250 || millis > 60000)
            throw new IllegalArgumentException("SP-API Redis cooldown must be 250..60000 ms");
        this.cooldown = Duration.ofMillis(millis);
        this.bursts = new SpApiBurstPolicy(approvedBursts);
        this.meters = meters;
    }

    @Override
    public void requirePermit(SpApiSellerScope seller, Class<?> apiFamily) {
        Objects.requireNonNull(seller, "Approved Seller scope required");
        Objects.requireNonNull(apiFamily, "SDK family required");
        if (!apiFamily.getName().startsWith("software.amazon.spapi.api.")
            || !apiFamily.getSimpleName().endsWith("Api"))
            throw new SecurityException("Unapproved API family");
        String key = "merchivra:spapi:quota:v1:" + seller.region().name()
            + ":" + apiFamily.getSimpleName() + ":" + sha256(seller.sellerId());
        reserve(key);
    }

    @Override
    public void requireVerificationPermit(SpApiRegion region) {
        Objects.requireNonNull(region, "Region required");
        reserve("merchivra:spapi:quota:v1:verification:" + region.name());
    }

    @Override
    public void requireLegacyPermit(SpApiRegion region, SpApiOperation operation,
        String approvedRefreshToken) {
        String key = legacyKey(region, operation, approvedRefreshToken);
        try {
            // All three keys are checked/admitted atomically on Redis server time.
            Long waitMillis = redis.execute(OPERATION_GCRA,
                List.of(key + ":next", key + ":interval", key + ":429"),
                Long.toString(cooldown.toMillis()), Integer.toString(bursts.capacity(operation)));
            if (waitMillis == null || waitMillis < 0) {
                admission(operation, "store_unavailable");
                throw new SpApiAdmissionException(SpApiAdmissionException.Reason.STORE_UNAVAILABLE);
            }
            if (waitMillis > 0) {
                admission(operation, "cooldown");
                throw new SpApiAdmissionException(SpApiAdmissionException.Reason.COOLDOWN,
                    Duration.ofMillis(Math.min(3_600_000, waitMillis)));
            }
            admission(operation, "allowed");
        } catch (DataAccessException ex) {
            admission(operation, "store_unavailable");
            throw new SpApiAdmissionException(SpApiAdmissionException.Reason.STORE_UNAVAILABLE);
        }
    }

    @Override
    public void observeLegacyResponse(SpApiRegion region, SpApiOperation operation,
        String approvedRefreshToken, int status, String observedRate, Duration retryAfter) {
        String key = legacyKey(region, operation, approvedRefreshToken);
        response(operation, status);
        try {
            // Header is best-effort and cannot account for all Amazon usage plans.
            if (((status >= 200 && status < 300) || status == 400 || status == 404)
                && observedRate != null) {
                try {
                    double rate = Double.parseDouble(observedRate.trim());
                    if (Double.isFinite(rate) && rate > 0) {
                        long interval = Math.max(250, Math.min(3_600_000,
                            (long) Math.ceil(1000.0 / rate)));
                        redis.opsForValue().set(key + ":interval",
                            Long.toString(interval), PLAN_TTL);
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid, untrusted response metadata must not change budgets.
                }
            }
            if (status == 429) {
                Duration delay = SpApiRetryPolicy.nextDelay(1, retryAfter);
                redis.execute(FENCE_SCRIPT, List.of(key + ":429"),
                    Long.toString(delay.toMillis()));
            }
        } catch (DataAccessException ignored) {
            // The request has already been sent: don't replay merely due to
            // optional metadata storage failure. Next admission fails closed.
        }
    }

    private void admission(SpApiOperation operation, String result) {
        if (meters != null) meters.counter("merchivra.spapi.admission",
            "operation", operation.name(), "result", result).increment();
    }

    private void response(SpApiOperation operation, int status) {
        if (meters == null) return;
        String outcome = status == 429 ? "429" : status >= 500 ? "5xx"
            : status >= 400 ? "4xx" : status >= 200 && status < 300 ? "2xx" : "other";
        meters.counter("merchivra.spapi.response",
            "operation", operation.name(), "status", outcome).increment();
    }
    private static String legacyKey(SpApiRegion region, SpApiOperation operation,
        String approvedRefreshToken) {
        Objects.requireNonNull(region, "Region required");
        Objects.requireNonNull(operation, "Operation required");
        if (approvedRefreshToken == null || approvedRefreshToken.isBlank())
            throw new SecurityException("Approved Seller grant required");
        return "merchivra:spapi:quota:v1:legacy:" + region.name() + ":"
            + operation.name() + ":" + sha256(approvedRefreshToken);
    }

    private Duration remaining(String key, Duration fallback) {
        Long millis=redis.getExpire(key, TimeUnit.MILLISECONDS);
        if (millis == null || millis < 1) return fallback;
        return Duration.ofMillis(Math.min(3_600_000, millis));
    }

    private void reserve(String key) {
        reserve(key, cooldown);
    }

    private void reserve(String key, Duration interval) {
        try {
            Boolean inserted = redis.opsForValue().setIfAbsent(key, "1", interval);
            if (inserted == null)
                throw new SpApiAdmissionException(SpApiAdmissionException.Reason.STORE_UNAVAILABLE);
            if (!inserted)
                throw new SpApiAdmissionException(SpApiAdmissionException.Reason.COOLDOWN,
                    remaining(key, interval));
        } catch (DataAccessException ex) {
            throw new SpApiAdmissionException(SpApiAdmissionException.Reason.STORE_UNAVAILABLE);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
