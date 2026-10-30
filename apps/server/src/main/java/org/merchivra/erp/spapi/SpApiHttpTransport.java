package org.merchivra.erp.spapi;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Mockable HTTP seam. Production uses JDK HttpClient; tests never require network. */
@FunctionalInterface
public interface SpApiHttpTransport {
    HttpResponse<String> send(HttpRequest request) throws Exception;
}
