package org.merchivra.erp.spapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * ID is logged or saved in plaintext. This is a conservative safety gate,
 * NOT a fully adaptive Amazon usage-plan / Retry-After token bucket.
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

    private final StringRedisTemplate redis;
    private final Duration cooldown;

    public RedisSpApiRateLimiter(StringRedisTemplate redis,
        @Value("${merchivra.spapi.rate-limit.cooldown-ms:2000}") long millis) {
        this.redis = Objects.requireNonNull(redis);
        if (millis < 250 || millis > 60000)
            throw new IllegalArgumentException("SP-API Redis cooldown must be 250..60000 ms");
        this.cooldown = Duration.ofMillis(millis);
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
            // Amazon 429 cooling fence is shared across backend instances.
            if (Boolean.TRUE.equals(redis.hasKey(key + ":429"))) {
                throw new SpApiAdmissionException(SpApiAdmissionException.Reason.COOLDOWN,
                    remaining(key + ":429", cooldown));
            }
            String learned = redis.opsForValue().get(key + ":interval");
            Duration interval = cooldown;
            if (learned != null) {
                try {
                    long millis = Long.parseLong(learned);
                    if (millis >= 250 && millis <= 3_600_000)
                        interval = Duration.ofMillis(millis);
                } catch (NumberFormatException ignored) {
                    // Missing or invalid advisory metadata -> conservative baseline.
                }
            }
            reserve(key, interval);
        } catch (DataAccessException ex) {
            throw new SpApiAdmissionException(SpApiAdmissionException.Reason.STORE_UNAVAILABLE);
        }
    }

    @Override
    public void observeLegacyResponse(SpApiRegion region, SpApiOperation operation,
        String approvedRefreshToken, int status, String observedRate, Duration retryAfter) {
        String key = legacyKey(region, operation, approvedRefreshToken);
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
        Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
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
