package org.merchivra.erp.operations;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.fba.FbaExceptionPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationsFbaExceptionBoundaryTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final FbaExceptionPermissionService permissions=mock(FbaExceptionPermissionService.class);
    private final OperationsFbaExceptionBoundary gate=
        new OperationsFbaExceptionBoundary(jdbc,permissions);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID marketplace=UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test void onlyFbaPurchaseEvidenceRulesEnterThisBoundary() {
        assertTrue(OperationsFbaExceptionBoundary.isFbaRule("FBA_PO_EVIDENCE_CHANGED"));
        assertTrue(OperationsFbaExceptionBoundary.isFbaRule("FBA_PO_EXCEPTION_OVERDUE"));
        for(String independent:List.of("LOW_STOCK","FBA_INBOUND_REVIEW",
              "REFUND_REVIEW","ADS_REPORT_REVIEW","INVENTORY_UNKNOWN"))
            assertFalse(OperationsFbaExceptionBoundary.isFbaRule(independent));
        gate.rule(tenant,marketplace,actor,"LOW_STOCK",
            FbaExceptionPermissionService.Capability.CONFIGURE);
        verifyNoInteractions(permissions);
        verifyNoInteractions(jdbc);
    }

    @Test void fbaRuleDelegatesToAlreadyAuditedOwnerCapability() {
        gate.rule(tenant,marketplace,actor,"FBA_PO_EXCEPTION_OVERDUE",
            FbaExceptionPermissionService.Capability.CONFIGURE);
        verify(permissions).require(tenant,marketplace,actor,
            FbaExceptionPermissionService.Capability.CONFIGURE);
    }

    @Test void tenantWideManualScanFailsClosedIfFbaConfigurationRestricted() {
        when(jdbc.queryForList(contains("fba_exception_owner_permission"),
                eq(UUID.class),eq(tenant),eq(actor)))
            .thenReturn(List.of(marketplace));
        var denied=assertThrows(ResponseStatusException.class,
            ()->gate.manualScan(tenant,actor));
        assertEquals(HttpStatus.FORBIDDEN,denied.getStatusCode());
        verifyNoInteractions(permissions);
    }

    @Test void tenantWideScanWithNoExplicitRestrictionsKeepsOwnerCompatibility() {
        when(jdbc.queryForList(contains("fba_exception_owner_permission"),
                eq(UUID.class),eq(tenant),eq(actor)))
            .thenReturn(List.of());
        assertDoesNotThrow(()->gate.manualScan(tenant,actor));
    }
}
