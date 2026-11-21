package org.merchivra.erp.finance;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** No mocks/clock/network: a canonical V102 digest must be reproducible offline. */
class SettlementIdentityFingerprintV181Test {
    private static final UUID TENANT=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MARKETPLACE=UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE=UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static CostSettlementLineEvidenceService.CompositeIdentity snapshot(
        String captureStatus,String externalOrderId,String signedAmount,
        String documentSha256) {
        return new CostSettlementLineEvidenceService.CompositeIdentity(
            "44444444-4444-4444-4444-444444444444",captureStatus,
            "55555555-5555-5555-5555-555555555555",17,
            "AMZN-SETTLEMENT-123","AMZN-DOC-123",documentSha256,
            "Order","ItemPrice","Principal",externalOrderId,signedAmount,
            "USD","66666666-6666-6666-6666-666666666666",
            "2026-11-20T00:00:00Z",null,null,null,
            false,false,false,false);
    }

    @Test void canonicalDigestHasStableGoldenVectorAndSha256Shape() {
        var frozen=snapshot("FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED",null,
            "-12.340000","a".repeat(64));
        String fingerprint=SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,frozen);
        assertEquals(
            "7e631d45241fcd7badcba701253987cdc90b1d71d434ba7b7585b07ca9ce62c8",
            fingerprint);
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertEquals(fingerprint,SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,frozen));
    }

    @Test void tenantMarketplaceAndCostSourceAllChangeTheFingerprint() {
        var frozen=snapshot("FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED",null,
            "-12.340000","a".repeat(64));
        String original=SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,frozen);
        assertNotEquals(original,SettlementIdentityFingerprintV181.digest(
            UUID.randomUUID(),MARKETPLACE,SOURCE,frozen));
        assertNotEquals(original,SettlementIdentityFingerprintV181.digest(
            TENANT,UUID.randomUUID(),SOURCE,frozen));
        assertNotEquals(original,SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,UUID.randomUUID(),frozen));
    }

    @Test void nullEmptyAndChangedFieldsDoNotCollide() {
        String state="FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED";
        String original=SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,snapshot(state,null,"-12.340000","a".repeat(64)));
        assertNotEquals(original,SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,snapshot(state,"","-12.340000","a".repeat(64))));
        assertNotEquals(original,SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,snapshot(state,null,"-12.350000","a".repeat(64))));
        assertNotEquals(original,SettlementIdentityFingerprintV181.digest(
            TENANT,MARKETPLACE,SOURCE,snapshot(state,null,"-12.340000","b".repeat(64))));
    }

    @Test void legacyAssociationsCannotGainPretendCaptureFingerprint() {
        var legacy=snapshot("CAPTURE_NOT_AVAILABLE",null,
            "-12.340000","a".repeat(64));
        assertThrows(IllegalArgumentException.class,()->
            SettlementIdentityFingerprintV181.digest(TENANT,MARKETPLACE,SOURCE,legacy));
    }

    @Test void responseExplicitlySaysDerivedAtReadTimeAndNeverVerified() {
        var result=snapshot("FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED",null,
            "-12.340000","a".repeat(64))
            .withReadTimeFingerprint(TENANT,MARKETPLACE,SOURCE);
        assertEquals(SettlementIdentityFingerprintV181.VERSION,result.fingerprintVersion());
        assertEquals("DERIVED_ON_READ_FROM_FROZEN_V102",result.fingerprintOrigin());
        assertNotNull(result.fingerprintSha256());
        assertFalse(result.externalChargeVerified());
        assertFalse(result.duplicateDebitVerified());
        assertFalse(result.bankVerified());
        assertFalse(result.netProfitEligible());
    }
}
