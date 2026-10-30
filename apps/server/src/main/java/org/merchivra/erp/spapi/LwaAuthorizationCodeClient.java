package org.merchivra.erp.spapi;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Authorization-code exchange, entirely separate from access-token refresh. */
public final class LwaAuthorizationCodeClient {
    @FunctionalInterface public interface Sender {
        HttpResponse<String> send(HttpRequest request) throws Exception;
    }
    private final Sender sender;
    private final URI endpoint;
    private final String clientId, clientSecret, redirectUri;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public LwaAuthorizationCodeClient(Sender sender, URI endpoint,
                                      String clientId, String clientSecret, String redirectUri) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()
            || redirectUri == null || redirectUri.isBlank())
            throw new IllegalArgumentException("LWA authorization is not configured");
        if (!"https".equals(endpoint.getScheme())
            && !("http".equals(endpoint.getScheme()) && "localhost".equals(endpoint.getHost())))
            throw new IllegalArgumentException("LWA token URL must use HTTPS");
        this.sender=sender; this.endpoint=endpoint;
        this.clientId=clientId; this.clientSecret=clientSecret; this.redirectUri=redirectUri;
    }
    public static LwaAuthorizationCodeClient official(String id, String secret, String redirect) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return new LwaAuthorizationCodeClient(
            req -> client.send(req, HttpResponse.BodyHandlers.ofString()),
            LwaTokenClient.OFFICIAL_TOKEN_ENDPOINT, id, secret, redirect);
    }

    public String exchange(String code) throws Exception {
        if (code == null || !code.matches("[A-Za-z0-9._~|\\-]{8,1024}"))
            throw new IllegalArgumentException("Invalid authorization code");
        String data = "grant_type=authorization_code&code=" + enc(code)
            + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
            + "&redirect_uri=" + enc(redirectUri);
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(data)).build();
        HttpResponse<String> response = sender.send(req);
        if (response.statusCode() != 200)
            throw new IOException("LWA authorization code exchange failed (HTTP " + response.statusCode() + ")");
        JsonNode json = mapper.readTree(response.body());
        JsonNode refresh = json.get("refresh_token");
        if (refresh == null || !refresh.isTextual() || refresh.asText().isBlank())
            throw new IOException("LWA authorization did not return a refresh token");
        return refresh.asText();
    }
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
