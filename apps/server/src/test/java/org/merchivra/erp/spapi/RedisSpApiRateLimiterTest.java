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
    void legacyOperationLearnsRateWithoutStoringRawGrant() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(0L).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),anyString(),anyString());
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        String grant="SECRET-GRANT-NOT-FOR-REDIS";
        limiter.observeLegacyResponse(SpApiRegion.NA,SpApiOperation.GET_ORDER,grant,
            200,"2.0",Duration.ZERO);
        limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.GET_ORDER,grant);
        var learned=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(learned.capture(),eq("500"),eq(Duration.ofHours(24)));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<String>> keys=
            org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            keys.capture(),eq("2000"),eq("1"));
        assertEquals(3,keys.getValue().size());
        assertTrue(keys.getValue().stream().allMatch(k->k.contains(":GET_ORDER:")));
        assertTrue(keys.getValue().stream().noneMatch(k->k.contains(grant)));
        assertFalse(learned.getValue().contains(grant));
    }

    @Test
    void atomic429FenceDefersWithRetryHintAndNeverReservesSeparatePermit() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        doReturn(3200L).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),anyString(),anyString());
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        var denied=assertThrows(SpApiAdmissionException.class,
            ()->limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.SEARCH_ORDERS,
                "secret-refresh"));
        assertEquals(SpApiAdmissionException.Reason.COOLDOWN,denied.reason());
        assertEquals(Duration.ofMillis(3200),denied.retryAfter());
        verify(redis,never()).opsForValue();
    }

    @Test
    void redisScriptFailureAndMissingReplyFailClosed() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        var absent=assertThrows(SpApiAdmissionException.class,
            ()->limiter.requireLegacyPermit(SpApiRegion.EU,SpApiOperation.GET_ORDER,"test-grant"));
        assertEquals(SpApiAdmissionException.Reason.STORE_UNAVAILABLE,absent.reason());
        doThrow(new DataAccessResourceFailureException("sensitive socket info"))
            .when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(),anyString(),anyString());
        var failure=assertThrows(SpApiAdmissionException.class,
            ()->limiter.requireLegacyPermit(SpApiRegion.EU,SpApiOperation.GET_ORDER,"test-grant"));
        assertEquals(SpApiAdmissionException.Reason.STORE_UNAVAILABLE,failure.reason());
        assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test
    void reportsUseConservativeInitialOneMinuteBudgetNotTheOrdersDefault() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        doReturn(0L).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),anyString(),anyString());
        var limiter=new RedisSpApiRateLimiter(redis,2000);
        limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.GET_REPORTS,"test-grant");
        limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.GET_REPORT_DOCUMENT,"test-grant");
        verify(redis,times(2)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),eq("60000"),eq("1"));
    }

    @Test
    void explicitBurstIsPassedOnlyToAtomicOperationScript() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        doReturn(0L).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),anyString(),anyString());
        var limiter=new RedisSpApiRateLimiter(redis,2000,"SEARCH_ORDERS=3");
        limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.SEARCH_ORDERS,"test-grant");
        limiter.requireLegacyPermit(SpApiRegion.NA,SpApiOperation.GET_ORDER,"test-grant");
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),eq("2000"),eq("3"));
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),eq("2000"),eq("1"));
    }
}
