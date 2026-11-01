package org.merchivra.erp.spapi;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import software.amazon.spapi.api.sellers.v1.SellersApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSpApiRateLimiterTest {
    @Test
    void sameSellerAndFamilyConsumeAtomicRedisPermitWithoutPlaintextSellerId() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),eq("1"),eq(Duration.ofMillis(2000))))
            .thenReturn(true,false);
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        var seller=new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),
            "CONFIDENTIAL-SELLER-123","ATVPDKIKX0DER",SpApiRegion.NA,
            "SECRET-REFRESH-TOKEN",Set.of("Inventory and Order Tracking"));
        limiter.requirePermit(seller,SellersApi.class);
        var denied=assertThrows(IllegalStateException.class,
            ()->limiter.requirePermit(seller,SellersApi.class));
        assertTrue(denied.getMessage().contains("cooldown"));
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values,times(2)).setIfAbsent(keys.capture(),eq("1"),eq(Duration.ofMillis(2000)));
        assertEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));
        assertFalse(keys.getValue().contains("CONFIDENTIAL-SELLER"));
        assertFalse(keys.getValue().contains("SECRET-REFRESH"));
        assertTrue(keys.getValue().startsWith("merchivra:spapi:quota:v1:NA:SellersApi:"));
    }

    @Test
    void redisFailureDeniesWithoutFallbackAndNoTokensInError() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new DataAccessResourceFailureException("redis down"));
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        var seller=new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),
            "SECRET-SELLER","ATVPDKIKX0DER",SpApiRegion.EU,"SECRET-TOKEN",Set.of());
        var error=assertThrows(IllegalStateException.class,
            ()->limiter.requirePermit(seller,SellersApi.class));
        assertTrue(error.getMessage().contains("denied"));
        assertFalse(error.getMessage().contains("SECRET"));
    }

    @Test
    void verificationHasItsOwnSharedRegionalCooldownAndBoundsAreChecked() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),eq("1"),eq(Duration.ofMillis(2000))))
            .thenReturn(true);
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        limiter.requireVerificationPermit(SpApiRegion.NA);
        verify(values).setIfAbsent("merchivra:spapi:quota:v1:verification:NA",
            "1",Duration.ofMillis(2000));
        assertThrows(IllegalArgumentException.class,()->new RedisSpApiRateLimiter(redis,0));
        assertThrows(IllegalArgumentException.class,()->new RedisSpApiRateLimiter(redis,60001));
    }
}
