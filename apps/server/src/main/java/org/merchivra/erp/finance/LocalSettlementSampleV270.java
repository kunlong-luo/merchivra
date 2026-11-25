package org.merchivra.erp.finance;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/**
 * Operator-local, offline sample arithmetic, not a trusted statement,
 * independent issuer verification, bank authentication or release approval.
 * No Spring context, database, credentials, network or output file.
 */
public final class LocalSettlementSampleV270 {
    private LocalSettlementSampleV270() {}
    record Check(String state,String reportSha256,String normalizedBankSha256,
        String currency,BigDecimal declared,BigDecimal lines,BigDecimal gross,
        BigDecimal lineDifference,BigDecimal bankDifference) {
        boolean amountsAgree(){return "LOCAL_SAMPLE_AMOUNTS_AGREE_UNVERIFIED".equals(state);}
    }

    private static String sha(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    // Reuse the same narrow, bounded report/normalized bank parsers as the
    // application, but recompute the totals directly from the selected bytes.
    static Check compare(byte[] reportBytes,boolean gzip,byte[] bankBytes) {
        var report=SettlementReportParser.parse(reportBytes,gzip);
        var bank=BankPayoutCsv.parse(bankBytes);
        var lines=report.lines().stream().map(SettlementReportParser.Line::amount)
            .reduce(BigDecimal.ZERO,BigDecimal::add);
        var declared=report.declaredNet();
        var lineDiff=declared==null?null:lines.subtract(declared);
        var matching=bank.stream().filter(e->report.settlementId().equals(e.settlementRef())).toList();
        String state;
        BigDecimal gross=null,bankDiff=null;
        if(declared==null)state="REPORT_DECLARED_NET_UNAVAILABLE";
        else if(lineDiff.signum()!=0)state="REPORT_INTERNAL_DIFFERENCE";
        else if(declared.signum()<=0)state="NONPOSITIVE_PAYOUT_OUT_OF_SCOPE";
        else if(matching.isEmpty())state="NO_MATCHING_BANK_ENTRY";
        else if(matching.size()!=1)state="AMBIGUOUS_BANK_ENTRY";
        else if(bank.size()!=1)state="BANK_SAMPLE_MUST_CONTAIN_ONE_ENTRY";
        else {
            var entry=matching.getFirst();
            if(!report.currency().equals(entry.currency()))state="FX_OUT_OF_SCOPE";
            else if(entry.fxRate()!=null&&entry.fxRate().compareTo(BigDecimal.ONE)!=0)
                state="SAME_CURRENCY_RATE_CONFLICT";
            else {
                gross=entry.credit().add(entry.fee());
                bankDiff=gross.subtract(declared);
                state=bankDiff.signum()==0?"LOCAL_SAMPLE_AMOUNTS_AGREE_UNVERIFIED":"BANK_AMOUNT_DIFFERENCE";
            }
        }
        return new Check(state,sha(reportBytes),sha(bankBytes),report.currency(),declared,lines,gross,
            lineDiff,bankDiff);
    }

    private static byte[] readBounded(String value,int limit) throws IOException {
        if(value==null||value.isBlank())throw new IOException("Missing local input");
        Path path=Path.of(value);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Not a regular file");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1);
            if(bytes.length==0||bytes.length>limit) {
                Arrays.fill(bytes,(byte)0);
                throw new IOException("Input outside bound");
            }
            return bytes;
        }
    }

    private static String decimal(BigDecimal n){return n==null?"null":"\""+n.toPlainString()+"\"";}
    private static String output(String state,String details) {
        return "{\"state\":\""+state+"\",\"issuerIdentityVerified\":false,"
            +"\"bankVerified\":false,\"financesCoverageVerified\":false,"
            +"\"accountingPolicyApproved\":false,\"ledgerPosted\":false,"
            +"\"productionApproved\":false,\"realizedNetProfit\":null"+details+"}";
    }

    static int run(Map<String,String> env,PrintStream out) {
        byte[] report=null,bank=null;
        try {
            String gzip=env.getOrDefault("MERCHIVRA_SAMPLE_GZIP","false");
            String details=env.getOrDefault("MERCHIVRA_SAMPLE_PRIVATE_DETAILS","false");
            if(!java.util.List.of("true","false").contains(gzip)
                ||!java.util.List.of("true","false").contains(details))
                throw new IllegalArgumentException("Invalid local flags");
            report=readBounded(env.get("MERCHIVRA_SAMPLE_REPORT"),SettlementReportParser.MAX_DOCUMENT_BYTES);
            bank=readBounded(env.get("MERCHIVRA_SAMPLE_BANK"),BankPayoutCsv.MAX_BYTES);
            var check=compare(report,Boolean.parseBoolean(gzip),bank);
            // Default output deliberately has no file paths, identity, hashes,
            // currency, amounts or dates. Private opt-in is never for CI.
            String extra="";
            if("true".equals(details))extra=",\"reportByteSha256\":\""+check.reportSha256()
                +"\",\"normalizedBankByteSha256\":\""+check.normalizedBankSha256()
                +"\",\"currency\":\""+check.currency()+"\",\"declaredNet\":"+decimal(check.declared())
                +",\"detailNet\":"+decimal(check.lines())+",\"bankGross\":"+decimal(check.gross())
                +",\"detailMinusDeclared\":"+decimal(check.lineDifference())
                +",\"bankGrossMinusDeclared\":"+decimal(check.bankDifference());
            out.println(output(check.state(),extra));
            return check.amountsAgree()?0:2;
        } catch(IOException|IllegalArgumentException|SecurityException invalid) {
            out.println(output("LOCAL_SAMPLE_INPUT_REJECTED",""));
            return 2;
        } finally {
            if(report!=null)Arrays.fill(report,(byte)0);
            if(bank!=null)Arrays.fill(bank,(byte)0);
        }
    }

    public static void main(String[] args) {
        if(args.length!=0) {
            System.out.println(output("LOCAL_SAMPLE_INPUT_REJECTED",""));
            System.exit(2);
        }
        int code=run(System.getenv(),System.out);
        if(code!=0)System.exit(code);
    }
}
