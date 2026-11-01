package org.merchivra.erp.ads;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AdsReportServiceTest {
    private static final UUID PROFILE=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static AdsReportService.ImportInput input(List<AdsReportService.CostInput> rows){
        return new AdsReportService.ImportInput(PROFILE,"V66-MOCK-REPORT-001","MOCK",
            LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),"USD",rows);
    }
    @Test void validatesCurrencyAndPerDateCampaignUniqueness(){
        var row=new AdsReportService.CostInput(LocalDate.of(2026,10,1),"CAMPAIGN-1","2.50");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->AdsReportService.validated(input(List.of(row,row)),"USD","MOCK"))
            .getStatusCode());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->AdsReportService.validated(input(List.of(row)),"EUR","MOCK"))
            .getStatusCode());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->AdsReportService.validated(input(List.of(row)),"USD","MANUAL_UNVERIFIED"))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->AdsReportService.validated(input(List.of(
                new AdsReportService.CostInput(LocalDate.of(2026,9,30),"CAMPAIGN-1","4")))
                ,"USD","MOCK")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->AdsReportService.validated(input(List.of(
                new AdsReportService.CostInput(LocalDate.of(2026,10,1),"CAMPAIGN-1","-1")))
                ,"USD","MOCK")).getStatusCode());
        assertEquals(2,AdsReportService.validated(input(List.of(row,
            new AdsReportService.CostInput(LocalDate.of(2026,10,2),"CAMPAIGN-1","0")))
            ,"USD","MOCK").size());
    }
    @Test void consentProtocolIsAdsOnlyAndNeverReusesSpapiEndpoint(){
        var uri=AdsConsentProtocol.prepare("EU",
            "amzn1.application-oa2-client.0123456789ABCDEF",
            "https://example.org/api/ads/callback",
            "A".repeat(40),"B".repeat(48));
        assertEquals("eu.account.amazon.com",uri.getHost());
        assertTrue(uri.toString().contains("advertising%3A%3Acampaign_management"));
        assertTrue(uri.toString().contains("code_challenge_method=S256"));
        assertThrows(IllegalArgumentException.class,()->
            AdsConsentProtocol.prepare("NA","invalid","http://localhost", "A".repeat(40),
                "B".repeat(48)));
        assertThrows(IllegalArgumentException.class,()->
            AdsConsentProtocol.prepare("OTHER",
                "amzn1.application-oa2-client.0123456789ABCDEF",
                "https://example.org", "A".repeat(40),"B".repeat(48)));
    }
}
