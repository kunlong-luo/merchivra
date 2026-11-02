package org.merchivra.erp.fba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FbaExceptionPermissionServiceTest {
    @Test void eachCapabilityIsIndependentAndNoAutomaticEscalation() {
        var grants=new FbaExceptionPermissionService.Grants(
            true,false,true,false,false,false);
        assertTrue(grants.allowed(FbaExceptionPermissionService.Capability.VIEW));
        assertTrue(grants.allowed(FbaExceptionPermissionService.Capability.REVIEW));
        assertFalse(grants.allowed(FbaExceptionPermissionService.Capability.ASSIGN));
        assertFalse(grants.allowed(FbaExceptionPermissionService.Capability.RESOLVE));
        assertFalse(grants.allowed(FbaExceptionPermissionService.Capability.CONFIGURE));
        assertFalse(grants.allowed(FbaExceptionPermissionService.Capability.MANAGE));
        assertEquals("101000",grants.encoded());
    }
    @Test void noViewDoesNotGrantAnyDefaultRights() {
        var grants=new FbaExceptionPermissionService.Grants(
            false,false,false,false,false,false);
        for(var capability:FbaExceptionPermissionService.Capability.values())
            assertFalse(grants.allowed(capability));
        assertEquals("000000",grants.encoded());
    }
}
