package org.merchivra.erp.finance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReportParserTest {
    private static final String DOCUMENT="""
        settlement-id\tsettlement-start-date\tsettlement-end-date\tdeposit-date\ttotal-amount\tcurrency
        SETTLE-2026-0001\t2026-10-01\t2026-10-14\t2026-10-15\t17.50\tUSD
        settlement-id\ttransaction-type\torder-id\tamount-type\tamount-description\tamount
        SETTLE-2026-0001\tOrder\tORDER-100\tItemPrice\tPrincipal\t20.00
        SETTLE-2026-0001\tOrder\tORDER-100\tItemFees\tCommission\t-2.50
        """;

    @Test
    void parsesMetadataAndSignedRowsWithoutCopyingUnknownFields() {
        var value=SettlementReportParser.parse(DOCUMENT.getBytes(StandardCharsets.UTF_8),false);
        assertEquals("SETTLE-2026-0001",value.settlementId());
        assertEquals("USD",value.currency());
        assertEquals(0,value.declaredNet().compareTo(new java.math.BigDecimal("17.50")));
        assertEquals(2,value.lines().size());
        assertEquals("-2.50",value.lines().get(1).amount().toPlainString());
        assertEquals("Commission",value.lines().get(1).amountDescription());
        assertEquals("ORDER-100",value.lines().get(0).orderId());
    }

    @Test
    void decodesBoundedGzipWithoutWritingTemporaryFiles() throws Exception {
        var out=new ByteArrayOutputStream();
        try(var gz=new GZIPOutputStream(out)) {
            gz.write(DOCUMENT.getBytes(StandardCharsets.UTF_8));
        }
        var value=SettlementReportParser.parse(out.toByteArray(),true);
        assertEquals(2,value.lines().size());
        assertThrows(IllegalArgumentException.class,
            ()->SettlementReportParser.parse(out.toByteArray(),false));
    }

    @Test
    void rejectsMixedCurrenciesAndBrokenOrRepeatedFieldsFailClosed() {
        String body="""
            settlement-id\tcurrency\ttransaction-type\tamount
            SETTLE-2026-0001\tUSD\tOrder\t10.00
            SETTLE-2026-0001\tEUR\tOrder\t2.00
            """;
        assertThrows(IllegalArgumentException.class,
            ()->SettlementReportParser.parse(body.getBytes(StandardCharsets.UTF_8),false));
        assertThrows(IllegalArgumentException.class,
            ()->SettlementReportParser.parse(body.replace("currency","amount")
                .getBytes(StandardCharsets.UTF_8),false));
        assertThrows(IllegalArgumentException.class,
            ()->SettlementReportParser.parse(body.replace("2.00","NaN")
                .getBytes(StandardCharsets.UTF_8),false));
        assertThrows(IllegalArgumentException.class,
            ()->SettlementReportParser.parse((body+"SETTLE-2026-OTHER\tUSD\tOrder\t3.00\n")
                .getBytes(StandardCharsets.UTF_8),false));
    }

    @Test
    void rejectsUnboundedDocumentAndQuotedPiiRows() {
        assertThrows(IllegalArgumentException.class,()->
            SettlementReportParser.parse(new byte[SettlementReportParser.MAX_DOCUMENT_BYTES+1],false));
        assertThrows(IllegalArgumentException.class,()->
            SettlementReportParser.parse(("settlement-id\ttransaction-type\tamount\tcurrency\n"+
                "SETTLE-2026-0001\t\"Customer name\"\t1.00\tUSD\n")
                .getBytes(StandardCharsets.UTF_8),false));
    }
}
