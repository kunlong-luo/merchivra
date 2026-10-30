package org.merchivra.erp.spapi;

import java.net.URI;

/** Amazon selling regions. Never accept an arbitrary target host from a request. */
public enum SpApiRegion {
    NA("https://sellingpartnerapi-na.amazon.com",
       "https://sandbox.sellingpartnerapi-na.amazon.com"),
    EU("https://sellingpartnerapi-eu.amazon.com",
       "https://sandbox.sellingpartnerapi-eu.amazon.com"),
    FE("https://sellingpartnerapi-fe.amazon.com",
       "https://sandbox.sellingpartnerapi-fe.amazon.com");

    private final URI production;
    private final URI sandbox;

    SpApiRegion(String production, String sandbox) {
        this.production = URI.create(production);
        this.sandbox = URI.create(sandbox);
    }

    public URI endpoint(boolean useSandbox) {
        return useSandbox ? sandbox : production;
    }
}
