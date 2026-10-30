package org.merchivra.erp.spapi;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Deliberately NOT an HTTP controller or generic proxy. A trusted worker must
 * supply the tenant-scoped seller's approved refresh token. Live egress is
 * disabled by default, as are all remote mutations. Amazon sandbox is separate
 * from the repository's synthetic Mock fixtures.
 */
public final class SpApiClient {
    private static final DateTimeFormatter AMAZON_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final SpApiHttpTransport transport;
    private final SpApiTokenProvider provider;
    private final Function<SpApiRegion, URI> endpoints;
    private final boolean enabled, allowWrites;

    public SpApiClient(SpApiHttpTransport transport, SpApiTokenProvider provider,
                       Function<SpApiRegion, URI> endpoints, boolean enabled, boolean allowWrites) {
        this.transport=transport; this.provider=provider; this.endpoints=endpoints;
        this.enabled=enabled; this.allowWrites=allowWrites;
    }

    public static SpApiClient official(java.net.http.HttpClient http,
                                       SpApiTokenProvider tokens, boolean sandbox,
                                       boolean enabled, boolean allowWrites) {
        // Endpoints are fixed by enum, never provided by user input or an API request.
        return new SpApiClient(r -> http.send(r, HttpResponse.BodyHandlers.ofString()),
            tokens, region -> region.endpoint(sandbox), enabled, allowWrites);
    }

    public SpApiResponse execute(SpApiRequest req, String approvedSellerRefreshToken) throws Exception {
        if (!enabled) throw new IllegalStateException("SP-API outbound integration disabled");
        if (req.operation().mutatesRemoteState() && !allowWrites)
            throw new SecurityException("SP-API remote writes disabled");
        URI base = endpoints.apply(req.region());
        if (!base.isAbsolute() || base.getHost() == null || base.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid SP-API endpoint");
        String resource = req.operation().path();
        for (var entry : req.pathVariables().entrySet()) {
            String key = "{" + entry.getKey() + "}";
            if (!resource.contains(key)) throw new IllegalArgumentException("Unexpected path variable");
            String value = entry.getValue();
            if (value == null || value.isBlank() || value.length() > 512)
                throw new IllegalArgumentException("Invalid path variable");
            resource = resource.replace(key, encode(value));
        }
        if (resource.contains("{") || resource.contains("}"))
            throw new IllegalArgumentException("Missing required path variable");
        if (req.query().entrySet().stream().anyMatch(e ->
            e.getValue() == null || e.getValue().isBlank()))
            throw new IllegalArgumentException("Empty SP-API query parameter");
        StringBuilder target = new StringBuilder(resource);
        if (!req.query().isEmpty()) {
            target.append('?');
            req.query().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                if (target.charAt(target.length()-1) != '?') target.append('&');
                target.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            });
        }
        URI destination = URI.create(base.toString() + target);
        String accessToken = provider.accessToken(approvedSellerRefreshToken);
        if (accessToken == null || accessToken.isBlank()) throw new IllegalStateException("LWA returned no access token");
        HttpRequest.Builder b = HttpRequest.newBuilder(destination)
            .timeout(Duration.ofSeconds(25))
            .header("Accept", "application/json")
            .header("User-Agent", "Merchivra/0.1 (Language=Java/25)")
            .header("x-amz-access-token", accessToken)
            .header("x-amz-date", AMAZON_TIME.format(Instant.now()));
        if ("GET".equals(req.operation().method())) b.GET();
        else b.header("Content-Type", "application/json")
              .method(req.operation().method(), HttpRequest.BodyPublishers.ofString(req.jsonBody()));
        // Don't retry immediately on 429/503; caller must apply operation/account
        // throttling and respect Retry-After. Blind retries amplify throttling.
        HttpResponse<String> response = transport.send(b.build());
        Map<String,String> responseHeaders = new LinkedHashMap<>();
        for (String name : new String[]{"x-amzn-requestid", "x-amzn-ratelimit-limit", "retry-after"}) {
            response.headers().firstValue(name).ifPresent(v -> responseHeaders.put(name, v));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Never log/throw raw response body or tokens.
            throw new SpApiHttpException(req.operation().name(), response.statusCode(),
                responseHeaders.getOrDefault("x-amzn-requestid", ""));
        }
        return new SpApiResponse(response.statusCode(), response.body(),
            Map.copyOf(responseHeaders));
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
