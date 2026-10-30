package org.merchivra.erp.spapi;

import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
import java.lang.reflect.InvocationTargetException;

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

    public SpApiOfficialSdkFactory(String clientId, String clientSecret,
                                   boolean enabled, boolean sandbox) {
        this.clientId=clientId; this.clientSecret=clientSecret;
        this.enabled=enabled; this.sandbox=sandbox;
    }

    public <T> T create(Class<T> apiClass, SpApiSellerScope approvedScope) {
        if (!enabled) throw new IllegalStateException("Official Amazon SDK is disabled");
        if (approvedScope == null) throw new IllegalArgumentException("Approved Seller scope required");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank())
            throw new IllegalStateException("LWA application credentials missing");
        if (!apiClass.getName().startsWith("software.amazon.spapi.api.")
            || !apiClass.getSimpleName().endsWith("Api"))
            throw new IllegalArgumentException("Only generated Amazon API types supported");
        var credentials = LWAAuthorizationCredentials.builder()
            .clientId(clientId)
            .clientSecret(clientSecret)
            .refreshToken(approvedScope.refreshToken())
            .endpoint(LwaTokenClient.OFFICIAL_TOKEN_ENDPOINT.toString())
            .build();
        try {
            Class<?> builderType = Class.forName(apiClass.getName() + "$Builder");
            Object builder = builderType.getDeclaredConstructor().newInstance();
            builderType.getMethod("lwaAuthorizationCredentials", LWAAuthorizationCredentials.class)
                .invoke(builder, credentials);
            builderType.getMethod("endpoint", String.class)
                .invoke(builder, approvedScope.region().endpoint(sandbox).toString());
            Object api = builderType.getMethod("build").invoke(builder);
            return apiClass.cast(api);
        } catch (ReflectiveOperationException e) {
            // Never include method argument values or credentials in exceptions.
            throw new IllegalStateException("Unable to build official SP-API SDK family: "
                + apiClass.getSimpleName());
        }
    }
}
