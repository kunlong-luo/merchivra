package org.merchivra.erp.purchasing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseDeliveryServiceTest {
    private static final LocalDate TODAY=LocalDate.parse("2026-11-02");
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseDeliveryService service=new PurchaseDeliveryService(jdbc,
        Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"),ZoneOffset.UTC));
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test void neverGuessEtaOrShipmentForApprovedPoWithoutAsn() {
        assertEquals("NO_ASN_RECORDED",PurchaseDeliveryService.classify(
            12,0,0,0,0,0,null,TODAY));
        assertEquals("ASN_AWAITING_DISPATCH",PurchaseDeliveryService.classify(
            12,12,0,0,0,0,TODAY.minusDays(10),TODAY));
        assertEquals("SHIPMENT_REPORTED",PurchaseDeliveryService.classify(
            12,0,12,0,0,0,TODAY.plusDays(7),TODAY));
    }

    @Test void onlyOutstandingSupplierReportedShipmentCanBeLate() {
        assertEquals("REPORTED_ETA_OVERDUE",PurchaseDeliveryService.classify(
            10,0,10,0,0,0,TODAY.minusDays(1),TODAY));
        assertEquals("REPORTED_ETA_DUE_SOON",PurchaseDeliveryService.classify(
            10,0,10,0,0,0,TODAY.plusDays(3),TODAY));
        assertEquals("SHIPMENT_REPORTED",PurchaseDeliveryService.classify(
            10,0,10,0,0,0,TODAY.plusDays(4),TODAY));
        assertEquals("NO_ASN_RECORDED",PurchaseDeliveryService.classify(
            10,0,0,0,0,0,TODAY.minusDays(1),TODAY));
    }

    @Test void localReceiptIsNotQcOrFbaReleaseAndDiscrepancyTakesPriority() {
        assertEquals("AWAITING_QC_RELEASE",PurchaseDeliveryService.classify(
            0,0,0,5,0,0,null,TODAY));
        assertEquals("QC_EVIDENCE_UNKNOWN",PurchaseDeliveryService.classify(
            0,0,0,0,5,0,null,TODAY));
        assertEquals("DISCREPANCY_REVIEW",PurchaseDeliveryService.classify(
            0,0,0,0,0,1,null,TODAY));
        assertEquals("LOCAL_RECEIVED",PurchaseDeliveryService.classify(
            0,0,0,0,0,0,null,TODAY));
    }

    @Test void missingTenantFailsBeforeReadingPoLines() {
        when(jdbc.queryForObject(contains("FROM tenant"),eq(Integer.class),eq(tenant)))
            .thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.overview(tenant)).getStatusCode());
        verify(jdbc,never()).query(contains("erp_purchase_order o"),
            any(RowMapper.class),any(Object[].class));
    }

    @Test void emptyActiveTenantDoesNotInventProgressOrZeroExternalDemand() {
        when(jdbc.queryForObject(contains("FROM tenant"),eq(Integer.class),eq(tenant)))
            .thenReturn(1);
        when(jdbc.query(contains("FROM erp_purchase_order o"),
            any(RowMapper.class),eq(tenant))).thenReturn(List.of());
        var overview=service.overview(tenant);
        assertEquals(TODAY.toString(),overview.asOfDateUtc());
        assertEquals(List.of(),overview.lines());
        assertEquals(0,overview.totals().lineCount());
        verify(jdbc).query(contains("WHERE o.tenant_id=?"),
            any(RowMapper.class),eq(tenant));
    }
}
