package org.merchivra.erp.finance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Internal-only Amazon pre-signed report document fetcher.
 * Never accepts a URL from HTTP clients. No redirect, credentials, URL logs,
 * file creation or unlimited streaming. Deploy with egress network ACLs too.
 */
@Component
public class SignedReportDocumentDownloader {
    private final HttpClient http;

    public SignedReportDocumentDownloader() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public SignedReportDocumentDownloader(HttpClient http) {
        this.http=java.util.Objects.requireNonNull(http);
    }

    public byte[] fetch(String signedUrl) throws IOException,InterruptedException {
        URI uri=validate(signedUrl);
        try {
            // DNS preflight is defense in depth; production egress restrictions
            // must also prohibit private/meta-data hosts at network level.
            InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());
            if(addresses.length==0)throw new IOException("Report URL DNS unresolved");
            for(var address:addresses) {
                if(address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress())
                    throw new IOException("Report URL resolves to private address");
            }
        } catch (java.net.UnknownHostException ex) {
            throw new IOException("Report URL DNS resolution failed");
        }
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
            .GET().header("Accept","application/octet-stream").build();
        final HttpResponse<java.io.InputStream> response;
        try {
            response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        } catch(IOException ex) {
            // Do not propagate a signed URL embedded in transport exceptions.
            throw new IOException("Report document retrieval failed");
        }
        try(var stream=response.body();var out=new ByteArrayOutputStream()) {
            if(response.statusCode()!=200)
                throw new IOException("Report document response not successful");
            byte[] buffer=new byte[8192];
            int length;
            while((length=stream.read(buffer))!=-1) {
                if(out.size()+length>SettlementReportParser.MAX_DOCUMENT_BYTES)
                    throw new IOException("Report document larger than accepted limit");
                out.write(buffer,0,length);
            }
            return out.toByteArray();
        } catch(IOException ex) {
            throw new IOException("Report document bytes unavailable or too large");
        }
    }

    public static URI validate(String signedUrl) {
        if(signedUrl==null || signedUrl.length()>8192)
            throw new SecurityException("Invalid report document address");
        final URI uri;
        try {uri=URI.create(signedUrl);} catch(IllegalArgumentException ex){
            throw new SecurityException("Invalid report document address");
        }
        String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null
            || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
            || (uri.getPort()!=-1 && uri.getPort()!=443))
            throw new SecurityException("Unapproved report document URL");
        String normalized=host.toLowerCase(Locale.ROOT);
        if(!normalized.matches("[a-z0-9.-]{5,253}")
            || !(normalized.endsWith(".amazonaws.com")
                || normalized.endsWith(".cloudfront.net")))
            throw new SecurityException("Unapproved report document host");
        return uri;
    }
}
