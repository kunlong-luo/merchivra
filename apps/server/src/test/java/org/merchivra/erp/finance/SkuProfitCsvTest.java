package org.merchivra.erp.finance;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkuProfitCsvTest {
    @Test
    void neutralizesFormulaInjectionAndDoesNotLeakTabsOrNewlines() {
        assertEquals("\"'=HYPERLINK(1)\"",SkuProfitCsv.cell("=HYPERLINK(1)"));
        assertEquals("\"'@cmd\"",SkuProfitCsv.cell("@cmd"));
        assertEquals("\"normal\"\"quoted\"",SkuProfitCsv.cell("normal\"quoted"));
        assertEquals("\"sample next\"",SkuProfitCsv.cell("sample\nnext"));
        assertEquals("",SkuProfitCsv.cell(null));
    }

    @Test
    void skippedSourceProfitIsBlankNeverInventedAsZero() {
        var totals=new SkuProfitPreviewEngine.Totals(1,0,1,0,0,
            "USD","0","0","0","0","0",false,false,false,false);
        var row=new SkuProfitPreviewEngine.OrderRow(
            "d3696be0-5f22-4d8b-8089-34d13d054a01","=malicious()",
            "2026-11-01T00:00:00Z",null,1,"NO_REVIEWED_LIVE_FINANCES",
            "USD",0,null,null,null,null,null);
        var page=new SkuProfitPreviewService.Preview(
            "2026-10-01","2026-11-01","UTC",0,30,true,true,
            "25.00",1,"MANUAL_UNVERIFIED",totals,List.of(),List.of(row));
        String csv=SkuProfitCsv.export(page);
        assertTrue(csv.startsWith("\uFEFForder_id"));
        assertTrue(csv.contains("\"'=malicious()\""));
        assertTrue(csv.contains("NO_REVIEWED_LIVE_FINANCES"));
        assertTrue(csv.contains(",\"\",\"\",\"\",\"\",\"\","));
        assertTrue(csv.contains("\"false\",\"false\""));
        assertFalse(csv.contains("25.00")); // Manual Ads are separate, never silently deducted
    }
}
