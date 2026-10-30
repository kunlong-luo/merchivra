package org.merchivra.erp.spapi;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpApiClientTest {
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of(
            "x-amzn-requestid", List.of("safe-request-123")), (k,v) -> true));
        return r;
    }

    private static SpApiSellerScope scope(Set<String> roles) {
        return new SpApiSellerScope(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "SELLER_123", "ATVPDKIKX0DER", SpApiRegion.NA,
            "fake-refresh-for-unit-test", roles);
    }

    @Test
    void disabledClientCannotExchangeTokensOrMakeRequests() {
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger sent = new AtomicInteger();
        SpApiClient client = new SpApiClient(r -> {
            sent.incrementAndGet(); return response(200, "{}");
        }, refresh -> { tokens.incrementAndGet(); return "fake-token"; },
            r -> URI.create("https://sellingpartnerapi-na.amazon.com"), false, false);
        assertThrows(IllegalStateException.class, () -> new SpApiReadOperations(client)
            .getOrder(scope(Set.of()), "ORDER-001"));
        assertEquals(0, sent.get());
        assertEquals(0, tokens.get());
    }

    @Test
    void ordersPathTokenAndRegionAreSafeAndQueryIsEncoded() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        var transport = (SpApiHttpTransport) req -> {
            captured.set(req); return response(200, "{\"orders\":[]}");
        };
        SpApiClient client = new SpApiClient(transport, refresh -> "unit-access-token",
            r -> r.endpoint(true), true, false);
        var operations = new SpApiReadOperations(client);
        var response = operations.getOrder(scope(Set.of()), "AMZ/123 A");
        assertEquals(200, response.statusCode());
        assertEquals("safe-request-123", response.requestId());
        assertEquals("sandbox.sellingpartnerapi-na.amazon.com",
            captured.get().uri().getHost());
        assertTrue(captured.get().uri().getRawPath().endsWith("/AMZ%2F123%20A"));
        assertEquals("unit-access-token",
            captured.get().headers().firstValue("x-amz-access-token").orElseThrow());
        operations.searchOrders(scope(Set.of()), Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-02T00:00:00Z"), "token+ /=");
        assertTrue(captured.get().uri().getRawQuery().contains("paginationToken=token%2B%20%2F%3D"));
        assertFalse(captured.get().uri().getRawQuery().contains("BUYER"));
    }

    @Test
    void rejectsPiiFinanceWithoutRoleAndLocalPlaceholders() {
        assertThrows(IllegalArgumentException.class, () -> SpApiRequest.read(
            SpApiRegion.NA, SpApiOperation.SEARCH_ORDERS, Map.of(),
            Map.of("includedData", "FULFILLMENT,BUYER"), Set.of()));
        assertThrows(SecurityException.class, () -> SpApiRequest.read(
            SpApiRegion.EU, SpApiOperation.LIST_TRANSACTIONS, Map.of(),
            Map.of("postedAfter", "2026-10-01T00:00:00Z"), Set.of()));
        assertDoesNotThrow(() -> SpApiRequest.read(
            SpApiRegion.EU, SpApiOperation.LIST_TRANSACTIONS, Map.of(),
            Map.of(), Set.of("Finance and Accounting")));
        assertThrows(IllegalArgumentException.class, () -> SpApiRequest.read(
            SpApiRegion.NA, SpApiOperation.GET_FBA_INVENTORY, Map.of(),
            Map.of("startDateTime", "2026-01-01T00:00:00Z", "sellerSku", "A"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpApiSellerScope(
            UUID.randomUUID(), UUID.randomUUID(), "LOCAL-UUID", "ATVPDKIKX0DER",
            SpApiRegion.NA, "test-refresh", Set.of()));
    }

    @Test
    void throttledResponseDoesNotRetryImmediatelyOrExposeRemoteBody() {
        AtomicInteger attempts = new AtomicInteger();
        SpApiClient client = new SpApiClient(req -> {
            attempts.incrementAndGet(); return response(429, "token=SECRET buyer-info=SECRET");
        }, refresh -> "fake-access", r -> r.endpoint(true), true, false);
        SpApiHttpException e = assertThrows(SpApiHttpException.class, () ->
            new SpApiReadOperations(client).getOrder(scope(Set.of()), "123"));
        assertEquals(429, e.status());
        assertEquals(1, attempts.get());
        assertFalse(e.getMessage().contains("SECRET"));
    }

    @Test
    void remoteWritesRequireIndependentExplicitApproval() {
        SpApiClient client = new SpApiClient(req -> response(200, "{}"),
            refresh -> "fake-access", r -> r.endpoint(true), true, false);
        var request = new SpApiRequest(SpApiRegion.NA, SpApiOperation.CREATE_REPORT,
            Map.of(), Map.of(), "{\"reportType\":\"TEST\"}", Set.of());
        assertThrows(SecurityException.class, () -> client.execute(request, "fake-refresh"));
    }
}
