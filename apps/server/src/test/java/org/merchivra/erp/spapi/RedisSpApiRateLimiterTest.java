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
        var denied=assertThrows(SpApiAdmissionException.class,
            ()->limiter.requirePermit(seller,SellersApi.class));
        assertEquals(SpApiAdmissionException.Reason.COOLDOWN, denied.reason());
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
        var error=assertThrows(SpApiAdmissionException.class,
            ()->limiter.requirePermit(seller,SellersApi.class));
        assertEquals(SpApiAdmissionException.Reason.STORE_UNAVAILABLE, error.reason());
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

    @Test
    void legacyOperationLearnsPerOperationRateAndNeverStoresRawGrant() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(values.get(anyString())).thenReturn("500");
        when(values.setIfAbsent(anyString(),eq("1"),eq(Duration.ofMillis(500))))
            .thenReturn(true);
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        String grant="SECRET-GRANT-NOT-FOR-REDIS";
        limiter.observeLegacyResponse(SpApiRegion.NA,SpApiOperation.GET_ORDER,grant,
            200,"2.0",Duration.ZERO);
        limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.GET_ORDER,grant);
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(keys.capture(),eq("500"),eq(Duration.ofHours(24)));
        verify(values).setIfAbsent(keys.capture(),eq("1"),eq(Duration.ofMillis(500)));
        assertTrue(keys.getAllValues().stream().allMatch(k->!k.contains(grant)));
        assertTrue(keys.getAllValues().stream().allMatch(k->k.contains(":GET_ORDER:")));
    }

    @Test
    void legacyPenaltyFenceDefersBeforeReservingASecondPermit() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(redis.getExpire(anyString(),eq(java.util.concurrent.TimeUnit.MILLISECONDS)))
            .thenReturn(3200L);
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        var denied=assertThrows(SpApiAdmissionException.class,
            ()->limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.SEARCH_ORDERS,
                "secret-refresh"));
        assertEquals(SpApiAdmissionException.Reason.COOLDOWN,denied.reason());
        assertEquals(Duration.ofMillis(3200),denied.retryAfter());
        verify(redis,never()).opsForValue();
    }
}
