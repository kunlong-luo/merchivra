package org.merchivra.erp.spapi;

import java.util.Set;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import software.amazon.spapi.ApiException;
import software.amazon.spapi.ApiResponse;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.spapi.api.sellers.v1.SellersApi;
import software.amazon.spapi.api.orders.v2026_01_01.SearchOrdersApi;
import static org.junit.jupiter.api.Assertions.*;

class SpApiOfficialSdkFactoryTest {
    private static SpApiSellerScope fakeSeller() {
        return new SpApiSellerScope(UUID.randomUUID(), UUID.randomUUID(),
            "SELLER_FOR_OFFLINE_UNIT_TEST", "ATVPDKIKX0DER", SpApiRegion.NA,
            "fake-unusable-refresh", Set.of());
    }

    @Test
    void neverCreatesActiveSdkWhenDisabled() {
        var sdk = new SpApiOfficialSdkFactory("fake-client", "fake-secret", false, true);
        assertThrows(IllegalStateException.class, () -> sdk.create(SellersApi.class, fakeSeller()));
    }

    @Test
    void buildsOfficialGeneratedApiTypesWithoutCallingAmazon() {
        var sdk = new SpApiOfficialSdkFactory("fake-client", "fake-secret", true, true);
        assertNotNull(sdk.create(SellersApi.class, fakeSeller()));
        assertNotNull(sdk.create(SearchOrdersApi.class, fakeSeller()));
    }


    @Test
    void provisionalVerificationNeverRunsAgainstSandboxOrDisabledSdk() {
        var sandbox=new SpApiOfficialSdkFactory("fake-client","fake-secret",true,true);
        assertThrows(IllegalStateException.class, () ->
            sandbox.sellersForVerification(SpApiRegion.NA,"fake-offline-refresh"));
        var disabled=new SpApiOfficialSdkFactory("fake-client","fake-secret",false,false);
        assertThrows(IllegalStateException.class, () ->
            disabled.sellersForVerification(SpApiRegion.NA,"fake-offline-refresh"));
        var productionBuilder=new SpApiOfficialSdkFactory("fake-client","fake-secret",true,false);
        assertNotNull(productionBuilder.sellersForVerification(
            SpApiRegion.NA,"fake-offline-refresh"));
        assertThrows(IllegalArgumentException.class, () ->
            productionBuilder.sellersForVerification(SpApiRegion.NA,""));
    }

    @Test
    void rejectsNonSdkClassAndMockAccountScope() {
        var sdk = new SpApiOfficialSdkFactory("fake-client", "fake-secret", true, true);
        assertThrows(IllegalArgumentException.class, () -> sdk.create(String.class, fakeSeller()));
        assertThrows(IllegalArgumentException.class, () -> new SpApiSellerScope(
            UUID.randomUUID(), UUID.randomUUID(), "MOCK-FAKE", "ATVPDKIKX0DER",
            SpApiRegion.NA, "FAKE", Set.of()));
    }

    @Test
    void observedReadAcquiresOperationPermitAndLearnsOfficialSdkResponseHeaders() throws Exception {
        SpApiRateLimiter limiter=mock(SpApiRateLimiter.class);
        var sdk=new SpApiOfficialSdkFactory("fake-client","fake-secret",true,true,limiter);
        var seller=fakeSeller();
        String result=sdk.executeObservedRead(seller,SpApiOperation.GET_CATALOG_ITEM,
            ()->new ApiResponse<>(200,
                Map.of("X-Amzn-RateLimit-Limit",List.of("0.25")),"fixture-data"));
        assertEquals("fixture-data",result);
        verify(limiter).requireLegacyPermit(seller.region(),SpApiOperation.GET_CATALOG_ITEM,
            seller.refreshToken());
        verify(limiter).observeLegacyResponse(seller.region(),SpApiOperation.GET_CATALOG_ITEM,
            seller.refreshToken(),200,"0.25",Duration.ZERO);
    }

    @Test
    void sdk429HeadersAreObservedAndCallerMustScheduleRetry() {
        SpApiRateLimiter limiter=mock(SpApiRateLimiter.class);
        var sdk=new SpApiOfficialSdkFactory("fake-client","fake-secret",true,true,limiter);
        var seller=fakeSeller();
        assertThrows(ApiException.class,()->sdk.executeObservedRead(seller,
            SpApiOperation.GET_CATALOG_ITEM,()->{
                throw new ApiException(429,Map.of("Retry-After",List.of("45")),"redacted");
            }));
        verify(limiter).observeLegacyResponse(seller.region(),SpApiOperation.GET_CATALOG_ITEM,
            seller.refreshToken(),429,null,Duration.ofSeconds(45));
        assertThrows(SecurityException.class,()->sdk.executeObservedRead(
            seller,SpApiOperation.CREATE_REPORT,
            ()->new ApiResponse<>(200,Map.of(),"must-not-run")));
    }
}
