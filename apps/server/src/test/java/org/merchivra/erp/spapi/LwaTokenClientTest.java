package org.merchivra.erp.spapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LwaTokenClientTest {
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> json(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @Test
    void reusesAccessTokenInMemoryForSameSellerGrant() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> success = json(200, "{\"access_token\":\"unit-only-token\",\"expires_in\":3600}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(success);
        var tokens = new LwaTokenClient(http, URI.create("http://localhost:12345/token"),
            "fake-client", "fake-secret", Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC));
        assertEquals("unit-only-token", tokens.accessToken("fake-refresh-1"));
        assertEquals("unit-only-token", tokens.accessToken("fake-refresh-1"));
        assertEquals("unit-only-token", tokens.accessToken("fake-refresh-2"));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void doesNotLeakAmazonAuthenticationErrorBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> rejected = json(401, "confidential-refresh-and-client-secret");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(rejected);
        var tokens = new LwaTokenClient(http, URI.create("http://localhost:12345/token"),
            "fake-client", "fake-secret", Clock.systemUTC());
        var error = assertThrows(java.io.IOException.class,
            () -> tokens.accessToken("test-revoked-refresh"));
        assertFalse(error.getMessage().contains("confidential"));
        assertEquals("LWA token exchange failed, HTTP 401", error.getMessage());
    }

    @Test
    void rejectsNonHttpsRemoteEndpointsAndMissingRefreshToken() {
        HttpClient http = mock(HttpClient.class);
        assertThrows(IllegalArgumentException.class, () -> new LwaTokenClient(
            http, URI.create("http://api.amazon.com/auth/o2/token"), "client", "secret", Clock.systemUTC()));
        var tokens = new LwaTokenClient(http, LwaTokenClient.OFFICIAL_TOKEN_ENDPOINT,
            "fake-client", "fake-secret", Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> tokens.accessToken(""));
        verifyNoInteractions(http);
    }
}
