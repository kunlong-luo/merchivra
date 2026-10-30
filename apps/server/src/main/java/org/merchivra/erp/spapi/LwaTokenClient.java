package org.merchivra.erp.spapi;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LWA refresh-token exchange and in-memory access-token cache.
 *
 * Caller owns secure per-Seller refresh token storage. This class has no file
 * persistence, controller, OAuth consent handler or token logging.
 */
public final class LwaTokenClient implements SpApiTokenProvider {
    public static final URI OFFICIAL_TOKEN_ENDPOINT = URI.create("https://api.amazon.com/auth/o2/token");
    private final HttpClient http;
    private final URI endpoint;
    private final String clientId, clientSecret;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private record Cached(String token, Instant expiry) {}

    public LwaTokenClient(HttpClient http, URI endpoint, String clientId,
                          String clientSecret, Clock clock) {
        if (clientId == null || clientId.isBlank() ||
            clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("LWA app configuration missing");
        }
        if (!"https".equals(endpoint.getScheme()) &&
            !("http".equals(endpoint.getScheme()) && "localhost".equals(endpoint.getHost()))) {
            throw new IllegalArgumentException("LWA requires HTTPS (except local test)");
        }
        this.http=http; this.endpoint=endpoint; this.clientId=clientId;
        this.clientSecret=clientSecret; this.clock=clock;
    }

    public static LwaTokenClient official(HttpClient http, String clientId, String clientSecret) {
        return new LwaTokenClient(http, OFFICIAL_TOKEN_ENDPOINT, clientId, clientSecret, Clock.systemUTC());
    }

    @Override
    public synchronized String accessToken(String refreshToken) throws IOException, InterruptedException {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Seller refresh token missing");
        }
        String key = digest(refreshToken);
        var stored = cache.get(key);
        if (stored != null && clock.instant().isBefore(stored.expiry())) return stored.token();

        String form = "grant_type=refresh_token&client_id=" + encode(clientId)
            + "&client_secret=" + encode(clientSecret)
            + "&refresh_token=" + encode(refreshToken);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Do not leak Amazon error body: it can contain sensitive grant metadata.
            throw new IOException("LWA token exchange failed, HTTP " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        JsonNode access = json.get("access_token"), ttl = json.get("expires_in");
        if (access == null || access.asText().isBlank() || ttl == null) {
            throw new IOException("LWA response has no usable access token");
        }
        long seconds = ttl.asLong();
        if (seconds <= 0) throw new IOException("LWA token expiry invalid");
        String token = access.asText();
        cache.put(key, new Cached(token, clock.instant().plusSeconds(Math.max(1, seconds - 60))));
        return token;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
