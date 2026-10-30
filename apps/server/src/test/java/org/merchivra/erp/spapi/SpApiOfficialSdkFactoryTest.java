package org.merchivra.erp.spapi;

import java.util.Set;
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
}
