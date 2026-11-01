package org.merchivra.erp.spapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
        Objects.requireNonNull(region, "Region required");
        Objects.requireNonNull(operation, "Operation required");
        if (approvedRefreshToken == null || approvedRefreshToken.isBlank())
            throw new SecurityException("Approved Seller grant required");
        // Legacy HTTP calls lack a verified Seller ID, so use a hash of the
        // server-only grant and operation. Never store or log raw tokens.
        reserve("merchivra:spapi:quota:v1:legacy:" + region.name() + ":"
            + operation.name() + ":" + sha256(approvedRefreshToken));
    }

    private void reserve(String key) {
        try {
            Boolean inserted = redis.opsForValue().setIfAbsent(key, "1", cooldown);
            if (inserted == null)
                throw new IllegalStateException("Redis admission result unknown; SP-API denied");
            if (!inserted)
                throw new IllegalStateException("SP-API Redis cooldown active; outbound deferred");
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Redis admission unavailable; SP-API denied");
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
