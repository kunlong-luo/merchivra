package org.merchivra.erp.finance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignedReportDocumentDownloaderTest {
    @Test
    void allowsOnlyExpectedAmazonSignedDocumentEndpoints() {
        assertEquals("d34o8swod1owfl.cloudfront.net",
            SignedReportDocumentDownloader.validate(
                "https://d34o8swod1owfl.cloudfront.net/path?X-Amz-Signature=secret").getHost());
        assertEquals("tortuga-prod-eu.s3-eu-west-1.amazonaws.com",
            SignedReportDocumentDownloader.validate(
                "https://tortuga-prod-eu.s3-eu-west-1.amazonaws.com/object").getHost());
        for(var url:new String[]{
            "http://a.cloudfront.net/file",
            "https://localhost/a",
            "https://127.0.0.1/a",
            "https://a.cloudfront.net.evil.com/file",
            "https://a.amazonaws.com@evil.example/file",
            "https://a.amazonaws.com:1234/file",
            "https://a.amazonaws.com/file#fragment",
            "file:///etc/passwd",
            "https://user:pass@a.amazonaws.com/file"
        })assertThrows(SecurityException.class,
            ()->SignedReportDocumentDownloader.validate(url),url);
    }
}
