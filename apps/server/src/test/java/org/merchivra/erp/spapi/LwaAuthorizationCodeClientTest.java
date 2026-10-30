package org.merchivra.erp.spapi;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LwaAuthorizationCodeClientTest {
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status,String json) {
        HttpResponse<String> r=mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(json);
        return r;
    }

    @Test
    void postsCorrectLwaGrantAndNeverReturnsAccessTokenToVault() throws Exception {
        var captured=new AtomicReference<HttpRequest>();
        var exchange=new LwaAuthorizationCodeClient(req -> {
            captured.set(req);
            return response(200, "{\"refresh_token\":\"fake-refresh-only\",\"access_token\":\"fake-access\"}");
        }, URI.create("http://localhost:18080/token"),"fake-client","fake-secret",
            "https://merchivra.example/api/v1/spapi/oauth/callback");
        assertEquals("fake-refresh-only",exchange.exchange("fake-code-1234"));
        assertEquals("POST",captured.get().method());
        String body=new String(captured.get().bodyPublisher().orElseThrow()
            .contentLength()>0?publisherBody(captured.get()):new byte[0]);
        assertTrue(body.contains("grant_type=authorization_code"));
        assertTrue(body.contains("redirect_uri=https%3A%2F%2Fmerchivra.example"));
        assertTrue(body.contains("client_id=fake-client"));
        assertFalse(body.contains("fake-access"));
    }

    private static byte[] publisherBody(HttpRequest request) throws Exception {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription sub) { sub.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer data) { byte[] b=new byte[data.remaining()];data.get(b);output.writeBytes(b); }
            public void onError(Throwable ex) { throw new IllegalStateException(ex); }
            public void onComplete() {}
        });
        return output.toByteArray();
    }

    @Test
    void exchangeFailureNeverLeaksRemotePayloadAndMissingRefreshFailsClosed() {
        var bad=new LwaAuthorizationCodeClient(req -> response(401,"secret=NOT_FOR_LOGS"),
            URI.create("http://localhost:18080/token"),"fake-client","fake-secret",
            "https://merchivra.example/api/v1/spapi/oauth/callback");
        var err=assertThrows(java.io.IOException.class,()->bad.exchange("fake-code-1234"));
        assertFalse(err.getMessage().contains("NOT_FOR_LOGS"));
        var incomplete=new LwaAuthorizationCodeClient(req -> response(200,"{\"access_token\":\"only-access\"}"),
            URI.create("http://localhost:18080/token"),"fake-client","fake-secret",
            "https://merchivra.example/api/v1/spapi/oauth/callback");
        assertThrows(java.io.IOException.class,()->incomplete.exchange("fake-code-1234"));
        assertThrows(IllegalArgumentException.class,()->bad.exchange("short"));
    }
}
