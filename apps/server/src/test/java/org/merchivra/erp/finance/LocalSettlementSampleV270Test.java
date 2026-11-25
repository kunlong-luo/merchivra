package org.merchivra.erp.finance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalSettlementSampleV270Test {
    private static final String REPORT="settlement-id\ttotal-amount\tcurrency\n"
        +"SETTLE-270-PRIVATE\t17.50\tUSD\n"
        +"settlement-id\ttransaction-type\torder-id\tamount\n"
        +"SETTLE-270-PRIVATE\tOrder\tORDER-PRIVATE\t20.00\n"
        +"SETTLE-270-PRIVATE\tOrder\tORDER-PRIVATE\t-2.50\n";
    private static final String BANK=BankPayoutCsv.HEADER+"\n"
        +"ENTRY-270-PRIVATE,SETTLE-270-PRIVATE,2026-11-01,USD,17.00,0.50,\n";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static LocalSettlementSampleV270.Check check(String report,String bank) {
        return LocalSettlementSampleV270.compare(bytes(report),false,bytes(bank));
    }
    @Test void exactOriginalCurrencyAndFeeArithmetic() {
        var result=check(REPORT,BANK);
        assertTrue(result.amountsAgree());
        assertEquals("17.50",result.lines().toPlainString());
        assertEquals("17.50",result.gross().toPlainString());
        assertEquals(0,result.bankDifference().signum());
        assertEquals(64,result.reportSha256().length());
        assertNotEquals(result.reportSha256(),result.normalizedBankSha256());
        var micro=check(REPORT,BANK.replace("17.00","16.999999"));
        assertEquals("BANK_AMOUNT_DIFFERENCE",micro.state());
        assertEquals("-0.000001",micro.bankDifference().toPlainString());
    }
    @Test void missingDeclaredAndDisagreeingDetailNeverMatch() {
        assertEquals("REPORT_DECLARED_NET_UNAVAILABLE",check(REPORT.replace("\t17.50\t","\t\t"),BANK).state());
        assertEquals("REPORT_INTERNAL_DIFFERENCE",check(REPORT.replace("-2.50","-2.49"),BANK).state());
        assertEquals("NONPOSITIVE_PAYOUT_OUT_OF_SCOPE",check(
            REPORT.replace("17.50","0.00").replace("-2.50","-20.00"),BANK).state());
    }
    @Test void identityMultiplicityAndFxAreNotGuessed() {
        assertEquals("NO_MATCHING_BANK_ENTRY",check(REPORT,BANK.replace("SETTLE-270-PRIVATE","SETTLE-270-OTHER")).state());
        assertEquals("AMBIGUOUS_BANK_ENTRY",check(REPORT,BANK+
            "ENTRY-270-SECOND,SETTLE-270-PRIVATE,2026-11-01,USD,17.00,0.50,\n").state());
        assertEquals("BANK_SAMPLE_MUST_CONTAIN_ONE_ENTRY",check(REPORT,BANK+
            "ENTRY-270-SECOND,SETTLE-270-OTHER,2026-11-01,USD,17.00,0.50,\n").state());
        assertEquals("FX_OUT_OF_SCOPE",check(REPORT,BANK.replace("USD","EUR").replace(",\n",",1.2\n")).state());
        assertEquals("SAME_CURRENCY_RATE_CONFLICT",check(REPORT,BANK.replace(",\n",",1.2\n")).state());
        assertThrows(IllegalArgumentException.class,()->check(REPORT,BANK.replace("fx_rate","account_number")));
    }
    @Test void gzipHashesOriginalBytesNotDecompressedText() throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new GZIPOutputStream(out)){zip.write(bytes(REPORT));}
        var zipped=LocalSettlementSampleV270.compare(out.toByteArray(),true,bytes(BANK));
        assertTrue(zipped.amountsAgree());
        assertNotEquals(check(REPORT,BANK).reportSha256(),zipped.reportSha256());
    }
    @Test void localCliRedactsByDefaultAndOnFailure(@TempDir Path dir) throws Exception {
        var report=dir.resolve("secret-report.tsv");var bank=dir.resolve("secret-bank.csv");
        Files.writeString(report,REPORT);Files.writeString(bank,BANK);
        var env=new HashMap<>(Map.of("MERCHIVRA_SAMPLE_REPORT",report.toString(),
            "MERCHIVRA_SAMPLE_BANK",bank.toString()));
        var out=new ByteArrayOutputStream();
        try(var stream=new PrintStream(out,true,StandardCharsets.UTF_8)) {
            assertEquals(0,LocalSettlementSampleV270.run(env,stream));
            String safe=out.toString(StandardCharsets.UTF_8);
            for(String secret:java.util.List.of("PRIVATE","secret-","17.50","USD","Sha256"))assertFalse(safe.contains(secret));
            assertTrue(safe.contains("\"bankVerified\":false"));
            assertTrue(safe.contains("\"realizedNetProfit\":null"));
            assertTrue(safe.contains("\"productionApproved\":false"));
            out.reset();
            env.put("MERCHIVRA_SAMPLE_PRIVATE_DETAILS","true");
            assertEquals(0,LocalSettlementSampleV270.run(env,stream));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"declaredNet\":\"17.50\""));
            assertFalse(out.toString(StandardCharsets.UTF_8).contains("PRIVATE"));
            out.reset();
            Files.writeString(bank,BANK.replace("17.00","16.00"));
            assertEquals(2,LocalSettlementSampleV270.run(env,stream));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("BANK_AMOUNT_DIFFERENCE"));
            out.reset();
            Files.write(bank,new byte[BankPayoutCsv.MAX_BYTES+1]);
            assertEquals(2,LocalSettlementSampleV270.run(env,stream));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("LOCAL_SAMPLE_INPUT_REJECTED"));
            assertFalse(out.toString(StandardCharsets.UTF_8).contains("secret-"));
        }
    }
}
