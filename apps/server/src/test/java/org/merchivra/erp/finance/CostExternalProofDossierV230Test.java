package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostExternalProofDossierV230Test {
    private static final UUID SOURCE=UUID.randomUUID(),MP=UUID.randomUUID(),
        MAKER=UUID.randomUUID(),CHECKER=UUID.randomUUID();
    private static CostSourceAuditIntegrityServiceV229.Evidence audit(
        String state,UUID reviewer,long all,long initial,long decision) {
        return CostSourceAuditIntegrityServiceV229.evaluate(SOURCE,MP,state,
            MAKER,reviewer,
            new CostSourceAuditIntegrityServiceV229.Counts(all,initial,decision));
    }
    private static CostAttributionEvidenceService.Assessment local(
        String status,boolean collision) {
        return new CostAttributionEvidenceService.Assessment(
            SOURCE.toString(),status,List.of(),1,1,0,1,1,1,0,0,
            true,collision,"NO_LOCAL_IDENTITY_MATCH_UNVERIFIED",
            false,false,false,false);
    }
    private static CostExternalProofDossierServiceV230.Dossier compose(
        String category,CostSourceAuditIntegrityServiceV229.Evidence audit,
        CostAttributionEvidenceService.Assessment local) {
        return CostExternalProofDossierServiceV230.compose(SOURCE,MP,
            new CostExternalProofDossierServiceV230.Scope(category,"USD"),
            audit,local);
    }
    private static void neverPostCost(
        CostExternalProofDossierServiceV230.Dossier dossier) {
        assertFalse(dossier.originalBytesVerified());
        assertFalse(dossier.issuerIdentityVerified());
        assertFalse(dossier.originalChargeVerified());
        assertFalse(dossier.duplicateDebitExcluded());
        assertFalse(dossier.settlementOrPaymentReconciled());
        assertFalse(dossier.actualLandedOrRefundCogsVerified());
        assertFalse(dossier.accountingPolicyApproved());
        assertFalse(dossier.eligibleForRealizedProfit());
        assertNull(dossier.realizedNetProfit());
        assertFalse(dossier.requiredExternalProof().isEmpty());
        assertTrue(dossier.requiredExternalProof().stream()
            .noneMatch(CostExternalProofDossierServiceV230.Requirement
                ::independentlyVerified));
        assertTrue(dossier.blockerCodes().contains(
            "INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED"));
    }
    @Test void categorySpecificRequirementsNeverDemandAmazonSettlementForAll() {
        String[][] cases={
            {"ADS_SPEND","AMAZON_ADS_REPORT_AND_BILLING_LINE"},
            {"INBOUND_FREIGHT","CARRIER_INVOICE_SHIPMENT_AND_RECEIPT"},
            {"FBA_STORAGE","AMAZON_FBA_FEE_REPORT_LINE"},
            {"FBM_SHIPPING","CARRIER_INVOICE_SHIPMENT_AND_RECEIPT"},
            {"RETURN_HANDLING","RETURN_DISPOSITION_RECEIPT_AND_COGS_REVERSAL"},
            {"IMPORT_DUTY","CUSTOMS_ENTRY_DUTY_AND_SHIPMENT"},
            {"PAYMENT_AND_FX","BANK_OR_PAYMENT_PROVIDER_ENTRY_AND_FX_POLICY"},
            {"OTHER_OPERATING","THIRD_PARTY_SERVICE_CONTRACT_AND_INVOICE"}
        };
        for(var row:cases) {
            var dossier=compose(row[0],audit("REVIEWED_UNVERIFIED",CHECKER,2,1,1),
                local("UNPROVEN",false));
            assertTrue(dossier.requiredExternalProof().stream()
                .anyMatch(e->row[1].equals(e.code())));
            assertTrue(dossier.requiredExternalProof().stream()
                .anyMatch(e->"ORIGINAL_DOCUMENT_BYTES_AND_SHA256".equals(e.code())));
            assertEquals("LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED",
                dossier.localDossierStatus());
            neverPostCost(dossier);
        }
        assertThrows(IllegalArgumentException.class,()->
            CostExternalProofDossierServiceV230.requirements("FAKE_PROOF"));
    }
    @Test void strongLocalMetadataAndIndependentOwnerReviewNeverVerifyExternalIssuer() {
        var dossier=compose("FBA_STORAGE",
            audit("REVIEWED_UNVERIFIED",CHECKER,2,1,1),
            local("SUPPORTING_LOCAL_METADATA_UNVERIFIED",false));
        assertTrue(dossier.localSourceAuditComplete());
        assertTrue(dossier.independentMetadataReviewComplete());
        assertFalse(dossier.localCollisionHeld());
        assertEquals("LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED",
            dossier.localDossierStatus());
        neverPostCost(dossier);
    }
    @Test void incompleteAuditBeatsPositiveSettlementEvidence() {
        var dossier=compose("ADS_SPEND",
            audit("REVIEWED_UNVERIFIED",CHECKER,0,0,0),
            local("SUPPORTING_LOCAL_METADATA_UNVERIFIED",false));
        assertEquals("LOCAL_AUDIT_INCONSISTENT_HOLD",
            dossier.localDossierStatus());
        assertFalse(dossier.localSourceAuditComplete());
        assertFalse(dossier.independentMetadataReviewComplete());
        assertTrue(dossier.blockerCodes().contains("V91_SOURCE_AUDIT_INCONSISTENT"));
        neverPostCost(dossier);
    }
    @Test void stagingRejectedAndCollisionRemainSeparateHolds() {
        var pending=compose("INBOUND_FREIGHT",audit("STAGED",null,1,1,0),
            local("UNPROVEN",false));
        assertEquals("LOCAL_METADATA_REVIEW_PENDING",
            pending.localDossierStatus());
        var rejected=compose("INBOUND_FREIGHT",audit("REJECTED",CHECKER,2,1,1),
            local("UNPROVEN",false));
        assertEquals("LOCAL_SOURCE_REJECTED",rejected.localDossierStatus());
        var conflict=compose("FBA_STORAGE",
            audit("REVIEWED_UNVERIFIED",CHECKER,2,1,1),
            local("CONFLICT_EVIDENCE",true));
        assertEquals("LOCAL_CROSS_SOURCE_CONFLICT_HOLD",
            conflict.localDossierStatus());
        assertTrue(conflict.blockerCodes().contains(
            "V165_V94_LOCAL_CROSS_SOURCE_COLLISION_HOLD"));
        neverPostCost(pending);
        neverPostCost(rejected);
        neverPostCost(conflict);
    }
    @Test void mixedSourceOrMarketPlaceEvidenceNeverMerges() {
        var good=audit("STAGED",null,1,1,0);
        var wrong=new CostAttributionEvidenceService.Assessment(
            UUID.randomUUID().toString(),"UNPROVEN",List.of(),
            0,0,0,0,0,0,0,0,false,false,"UNPROVEN",
            false,false,false,false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->compose("ADS_SPEND",good,wrong)).getStatusCode());
    }
    @Test void ownerSessionScopedAndNoStore() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostExternalProofDossierServiceV230.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var result=new CostExternalProofDossierControllerV230(access,service)
            .get(auth,req,MP,SOURCE);
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect(tenant,MP,SOURCE);
        verifyNoMoreInteractions(service);
    }
    @Test void readOnlySnapshotMustReuseV91V182AndNeverContainFinancialWrites()
        throws Exception {
        String source=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostExternalProofDossierServiceV230.java"));
        assertTrue(source.contains("Isolation.REPEATABLE_READ"));
        assertTrue(source.contains("sourceAudit.inspect(tenant,marketplace,sourceId)"));
        assertTrue(source.contains("attribution.assess(tenant,marketplace,sourceId)"));
        assertTrue(source.contains("s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?"));
        assertFalse(source.contains("INSERT INTO finance_"));
        assertFalse(source.contains("UPDATE finance_"));
        assertFalse(source.contains("DELETE FROM finance_"));
        var service=new CostExternalProofDossierServiceV230(
            mock(JdbcTemplate.class),mock(CostSourceAuditIntegrityServiceV229.class),
            mock(CostAttributionEvidenceService.class));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.inspect(null,MP,SOURCE)).getStatusCode());
    }
}
