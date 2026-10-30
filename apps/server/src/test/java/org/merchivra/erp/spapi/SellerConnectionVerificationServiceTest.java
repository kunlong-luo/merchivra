package org.merchivra.erp.spapi;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SellerConnectionVerificationServiceTest {
    private static SellerMarketplaceGateway.Participation p(String id,boolean active,boolean suspended) {
        return new SellerMarketplaceGateway.Participation(id,active,suspended);
    }
    @Test void requiresEveryConfiguredMarketplaceToBeActiveAndNotSuspended() {
        var configured=List.of("ATVPDKIKX0DER","A2EUQ1WTGCTBG2");
        assertNull(SellerConnectionVerificationService.validate(configured,List.of(
            p("ATVPDKIKX0DER",true,false),p("A2EUQ1WTGCTBG2",true,false))));
        assertEquals("MARKETPLACE_MISSING",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false))));
        assertEquals("NOT_PARTICIPATING",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false),
                p("A2EUQ1WTGCTBG2",false,false))));
        assertEquals("LISTINGS_SUSPENDED",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false),
                p("A2EUQ1WTGCTBG2",true,true))));
    }
    @Test void rejectsMalformedOrAmbiguousSellerResponse() {
        var configured=List.of("ATVPDKIKX0DER");
        assertEquals("EMPTY_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(configured,null));
        assertEquals("EMPTY_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(configured,List.of()));
        assertEquals("INVALID_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(
            configured,List.of(p("ATVPDKIKX0DER",true,false),p("ATVPDKIKX0DER",true,false))));
        assertEquals("INVALID_MARKETPLACE_RESPONSE",SellerConnectionVerificationService.validate(
            configured,List.of(p("",true,false))));
    }
    @Test void doesNotPretendSellersApiResponseContainsSellerIdOrRoles() {
        assertEquals(2,new SellerMarketplaceGateway.Participation("A",true,false).getClass()
            .getRecordComponents().length-1);
    }
}
