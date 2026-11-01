package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankPayoutCsvTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static final String ROW="ENTRY-10001,SETTLE-10001,2026-10-29,USD,120.50,2.50,\n";
    @Test void parsesOnlyNonSensitiveNormalizedBankReferences() {
        var rows=BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW));
        assertEquals(1,rows.size());
        assertEquals("ENTRY-10001",rows.getFirst().entryRef());
        assertEquals("120.50",rows.getFirst().credit().toPlainString());
        assertNull(rows.getFirst().fxRate());
        assertEquals("2.50",rows.getFirst().fee().toPlainString());
    }
    @Test void rejectsExtraPiiColumnAndDuplicateEntries() {
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+",bank_account\n"+ROW)));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW+ROW)));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW.replace("USD","usd"))));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW.replace("120.50","0"))));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW.replace("2.50","-1.00"))));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW.replace(",\n",",NaN\n"))));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n"+ROW.replace("ENTRY-10001","\"BUYER\""))));
    }
    @Test void rejectsOversizedFilesAndMixedUtf8Invalidity() {
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(new byte[BankPayoutCsv.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(new byte[]{(byte)0xc3,(byte)0x28}));
        assertThrows(IllegalArgumentException.class,()->
            BankPayoutCsv.parse(bytes(BankPayoutCsv.HEADER+"\n")));
    }
}
