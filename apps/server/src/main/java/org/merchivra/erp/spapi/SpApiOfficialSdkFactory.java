package org.merchivra.erp.spapi;

import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
import software.amazon.spapi.api.sellers.v1.SellersApi;

/**
 * Official Amazon SDK builder for all generated operation families.
 *
 * Only trusted server workers may construct these SDKs. The factory does NOT
 * accept browser account IDs or unverified scopes. Each API exposes mutating
 * operations, so an approved worker must apply business permission checks
 * before invoking an SDK method. No public HTTP endpoint exposes this factory.
 */
public final class SpApiOfficialSdkFactory {
    private final String clientId, clientSecret;
    private final boolean enabled, sandbox;
    private final SpApiRateLimiter rateLimiter;

    public SpApiOfficialSdkFactory(String clientId, String clientSecret,
                                   boolean enabled, boolean sandbox) {
        this(clientId,clientSecret,enabled,sandbox,SpApiRateLimiter.disabled());
    }

    public SpApiOfficialSdkFactory(String clientId, String clientSecret,
        boolean enabled, boolean sandbox, SpApiRateLimiter rateLimiter) {
        this.clientId=clientId; this.clientSecret=clientSecret;
        this.enabled=enabled; this.sandbox=sandbox;
        this.rateLimiter=java.util.Objects.requireNonNull(rateLimiter);
    }


    public <T> T create(Class<T> apiClass, SpApiSellerScope approvedScope) {
        if (approvedScope == null) throw new IllegalArgumentException("Approved Seller scope required");
        T api=build(apiClass,approvedScope.region(),approvedScope.refreshToken());
        rateLimiter.requirePermit(approvedScope,apiClass);
        return api;
    }

    /**
     * Provisional grant can call ONLY Sellers v1, NEVER general API families.
     * A sandbox response is not evidence of a live Seller authorization.
     */
    public SellersApi sellersForVerification(SpApiRegion region, String refreshToken) {
        if (sandbox) throw new IllegalStateException("Production Sellers verification cannot use sandbox");
        SellersApi api=build(SellersApi.class,region,refreshToken);
        rateLimiter.requireVerificationPermit(region);
        return api;
    }

    private <T> T build(Class<T> apiClass, SpApiRegion region, String refreshToken) {
        if (!enabled) throw new IllegalStateException("Official Amazon SDK is disabled");
        if (region == null || refreshToken == null || refreshToken.isBlank())
            throw new IllegalArgumentException("Server-side Seller grant required");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank())
            throw new IllegalStateException("LWA application credentials missing");
        if (!apiClass.getName().startsWith("software.amazon.spapi.api.")
            || !apiClass.getSimpleName().endsWith("Api"))
            throw new IllegalArgumentException("Only generated Amazon API types supported");
        var credentials = LWAAuthorizationCredentials.builder()
            .clientId(clientId)
            .clientSecret(clientSecret)
            .refreshToken(refreshToken)
            .endpoint(LwaTokenClient.OFFICIAL_TOKEN_ENDPOINT.toString())
            .build();
        try {
            Class<?> builderType = Class.forName(apiClass.getName() + "$Builder");
            Object builder = builderType.getDeclaredConstructor().newInstance();
            builderType.getMethod("lwaAuthorizationCredentials", LWAAuthorizationCredentials.class)
                .invoke(builder, credentials);
            builderType.getMethod("endpoint", String.class)
                .invoke(builder, region.endpoint(sandbox).toString());
            return apiClass.cast(builderType.getMethod("build").invoke(builder));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to build official SP-API SDK family: "
                + apiClass.getSimpleName());
        }
    }
}
