package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V181: scoped read-only original report/line identity, not a bank receipt. */
class CostSettlementLineIdentityV181Test {
    @Test void readonlyIdentityUsesAuthenticatedTenantNotAClientSuppliedTenant() {
        var access=mock(TenantAccessService.class);
        var svc=mock(CostSettlementLineEvidenceService.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),marketplace=UUID.randomUUID();
        UUID source=UUID.randomUUID(),link=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var controller=new CostSettlementLineEvidenceController(access,svc);
        var response=controller.compositeIdentity(auth,request,marketplace,source,link);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).identity(tenant,marketplace,source,link);
        verifyNoMoreInteractions(svc);
    }

    @Test void mismatchedTenantSourceOrLinkMustFailClosedBeforeRead() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CostSettlementLineEvidenceService(jdbc);
        var status=assertThrows(ResponseStatusException.class,()->
            service.identity(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND,status.getStatusCode());
        // Only an exact V94 tenant/Marketplace/source/link match authorizes
        // the separate new V102 metadata query.
        verify(jdbc).query(contains("finance_cost_settlement_line_link_v94"),
            any(org.springframework.jdbc.core.RowMapper.class),
            any(UUID.class),any(UUID.class),any(UUID.class),any(UUID.class));
    }

    @Test void databaseChecksScopeAndFreezesCompositeAndReportMetadata() throws Exception {
        String ddl=Files.readString(Path.of(
            "src/main/resources/db/migration/V102__settlement_line_composite_identity.sql"));
        for(String token:new String[]{
            "PRIMARY KEY (tenant_id,link_id)",
            "REFERENCES finance_cost_settlement_line_link_v94(tenant_id,id)",
            "REFERENCES finance_cost_source_v91(tenant_id,seller_marketplace_id,id)",
            "REFERENCES spapi_settlement_report(tenant_id,seller_marketplace_id,id)",
            "REFERENCES spapi_settlement_report_line(tenant_id,report_id,line_number)",
            "link_record.source_id,link_record.report_id",
            "report_record.document_sha256",
            "line_record.amount_description,line_record.external_order_id",
            "line_record.signed_amount,line_record.currency_code",
            "source_origin='LIVE' FOR SHARE",
            "IF TG_OP<>'INSERT' THEN",
            "BEFORE INSERT OR UPDATE OR DELETE",
            "BEFORE UPDATE OR DELETE ON spapi_settlement_report"
        })assertTrue(ddl.contains(token),token);
        assertFalse(ddl.contains("INSERT INTO finance_cost_settlement_line_identity_v102"));
        assertFalse(ddl.contains("UPDATE finance_profit_report"));
        assertFalse(ddl.contains("CREATE EXTENSION"));
        assertFalse(ddl.contains("BACKFILL"));
    }

    @Test void identityCaptureMustBeInTheSameTransactionAsV94AndAudit() throws Exception {
        var java=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostSettlementLineEvidenceService.java"));
        assertTrue(java.contains("captureCompositeIdentity(tenant,marketplace,sourceId,id,"));
        assertTrue(java.contains("finance_cost_settlement_line_identity_v102"));
        assertTrue(java.contains("FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED"));
        assertTrue(java.contains("CAPTURE_NOT_AVAILABLE"));
        assertTrue(java.contains("false,false,false,false"));
        assertTrue(java.contains("@Transactional\n    public Link attach("));
        assertTrue(java.indexOf("captureCompositeIdentity(tenant,marketplace,sourceId,id,")
            <java.indexOf("audit(tenant,id,actor,\"OPEN\")"));
        assertFalse(java.contains("independentExternalEvidenceVerified=true"));
    }
}
