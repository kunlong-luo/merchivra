package org.merchivra.erp.purchasing;

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

class SupplierAsnServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SupplierAsnService service=new SupplierAsnService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID order=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID line=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID warehouse=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID key=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID asnId=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final LocalDate eta=LocalDate.now(ZoneOffset.UTC).plusDays(10);

    private SupplierAsnService.Create create(int quantity) {
        return new SupplierAsnService.Create(key,line,warehouse,quantity,eta,"SUP-REF");
    }
    private SupplierAsnService.Asn stored(String status) {
        return new SupplierAsnService.Asn(asnId.toString(),order.toString(),
            line.toString(),warehouse.toString(),6,0,eta.toString(),"SUP-REF",
            status,"SHIPPED".equals(status)?"2026-10-31T00:00:00Z":null,
            "2026-10-31T00:00:00Z");
    }
    private void approved() {
        when(jdbc.queryForList(contains("FROM erp_purchase_order"),
            eq(UUID.class),eq(tenant),eq(order))).thenReturn(List.of(order));
    }
    private void storedAsn(String status) {
        when(jdbc.query(contains("FROM erp_purchase_asn a WHERE a.tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(asnId)))
            .thenReturn(List.of(stored(status)));
    }
    @Test void inputValidationDoesNotTouchDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.create(tenant,actor,order,create(0))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.create(tenant,actor,order,
                new SupplierAsnService.Create(key,line,warehouse,6,
                    LocalDate.now(ZoneOffset.UTC).plusDays(800),"SUP")))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.scan(tenant,actor,"MALFORMED")).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void unapprovedOrderCannotIssueSupplierShipmentNotice() {
        when(jdbc.queryForList(contains("FROM erp_purchase_order"),
            eq(UUID.class),eq(tenant),eq(order))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.create(tenant,actor,order,create(6))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void asnMustNotOverpromiseAgainstUnlinkedReceipts() {
        approved();
        when(jdbc.queryForList(contains("FROM erp_purchase_asn"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM erp_purchase_order_line"),
            eq(Integer.class),eq(tenant),eq(order),eq(line))).thenReturn(List.of(10));
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
        when(jdbc.queryForObject(contains("FROM erp_purchase_asn"),
            eq(Long.class),eq(tenant),eq(order),eq(line))).thenReturn(3L);
        when(jdbc.queryForObject(contains("FROM erp_purchase_receipt"),
            eq(Long.class),eq(tenant),eq(order),eq(line))).thenReturn(2L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.create(tenant,actor,order,create(6))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void exactRetryReusesOriginalAsnInsteadOfDuplicating() {
        approved();
        when(jdbc.queryForList(contains("FROM erp_purchase_asn"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of(asnId));
        storedAsn("DRAFT");
        assertEquals(asnId.toString(),service.create(tenant,actor,order,create(6)).id());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cancellationWithLinkedReceiptMustFail() {
        approved();
        when(jdbc.query(contains("SELECT status FROM erp_purchase_asn"),
            any(RowMapper.class),eq(tenant),eq(order),eq(asnId)))
            .thenReturn(List.of("SHIPPED"));
        when(jdbc.queryForObject(contains("FROM erp_purchase_receipt"),
            eq(Long.class),eq(tenant),eq(asnId))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.transition(tenant,actor,order,asnId,"cancel"))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void staleEtaChangeIsRejectedWithoutMutation() {
        approved();
        storedAsn("SHIPPED");
        var updated=new SupplierAsnService.ChangeEta(eta.plusDays(1),
            eta.minusDays(1),"Supplier delayed delivery");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.updateEta(tenant,actor,order,asnId,updated)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void noCrossTenantFindingMutation() {
        when(jdbc.query(contains("FROM erp_purchase_asn_finding"),
            any(RowMapper.class),eq(tenant),eq(asnId))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,asnId,"DONE")).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void scheduledScanOnlyWritesAdvisoryRowsAndScopedAudit() {
        when(jdbc.queryForList(contains("FROM tenant"),
            eq(UUID.class),eq(tenant))).thenReturn(List.of(tenant));
        var result=service.scan(tenant,null,"SCHEDULED");
        assertEquals("SCHEDULED",result.triggerType());
        verify(jdbc).update(contains("SUPPLIER_ASN_OVERDUE"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("SUPPLIER_ASN_DUE_SOON"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("UPDATE erp_purchase_asn_finding"),
            eq(tenant),any(UUID.class));
        verify(jdbc).update(contains("INSERT INTO erp_purchase_asn_scan_run"),
            any(UUID.class),eq(tenant),isNull(),eq("SCHEDULED"),eq(0),eq(0));
    }
}
