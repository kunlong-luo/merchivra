package org.merchivra.erp.finance;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.merchivra.erp.finance.SettlementReportImportService.*;
class SettlementCategoryTypesV280Test {
    private final UUID id=UUID.randomUUID();
    @Test void completeTypesPreserveNullAndCounts() {
        var types=List.of(new CategoryType("Order",null,"Principal",100),
            new CategoryType("Order","ItemFees","Commission",1));
        var result=finishCategoryTypes(id,101,101,types);
        assertEquals("REPORT_TYPES_READY",result.state());
        assertEquals(types,result.types());
        assertFalse(result.mappingApproved());
    }
    @Test void partialAndUnmappableNeverReturnTypes() {
        var partial=finishCategoryTypes(id,101,101,List.of(new CategoryType("Order",null,"Principal",100)));
        assertEquals("REPORT_TYPE_COVERAGE_HOLD",partial.state());
        assertTrue(partial.types().isEmpty());
        var invalid=finishCategoryTypes(id,1,1,List.of(new CategoryType(null,null,null,1)));
        assertEquals("REPORT_TYPE_UNMAPPABLE",invalid.state());
        assertTrue(invalid.types().isEmpty());
        var oversized=finishCategoryTypes(id,201,201,IntStream.range(0,201)
            .mapToObj(i->new CategoryType("Order",null,"Type"+i,1)).toList());
        assertEquals("REPORT_TYPE_LIMIT_EXCEEDED",oversized.state());
        assertTrue(oversized.types().isEmpty());
    }
}
