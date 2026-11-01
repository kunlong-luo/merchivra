package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict normalized bank-payout observation CSV. No bank account numbers,
 * IBAN, names, buyers, routing numbers or extra columns may be ingested.
 * Source remains MANUAL_UNVERIFIED even after any human review.
 */
public final class BankPayoutCsv {
    public static final int MAX_BYTES=64*1024;
    public static final int MAX_ROWS=50;
    public static final String HEADER=
        "entry_id,settlement_id,value_date,currency,credit_amount,withheld_fee,fx_rate";
    public record Entry(int line,String entryRef,String settlementRef,
        LocalDate valueDate,String currency,BigDecimal credit,
        BigDecimal fee,BigDecimal fxRate) {}
    private BankPayoutCsv() {}

    public static List<Entry> parse(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)
            throw new IllegalArgumentException("Bank CSV size must be 1..65536 bytes");
        final String text;
        try {
            text=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch(CharacterCodingException invalid) {
            throw new IllegalArgumentException("Bank CSV is not UTF-8");
        }
        String normalized=text.startsWith("\uFEFF")?text.substring(1):text;
        if(normalized.indexOf('"')>=0||normalized.indexOf('\0')>=0
            ||normalized.indexOf('\r')>=0 && !normalized.contains("\r\n"))
            throw new IllegalArgumentException("Unsupported bank CSV quoting/controls");
        var lines=normalized.split("\n",-1);
        if(lines.length<2||lines.length>MAX_ROWS+3)
            throw new IllegalArgumentException("Bank CSV row count outside bound");
        if(!stripCr(lines[0]).equals(HEADER))
            throw new IllegalArgumentException("Bank CSV header mismatch");
        List<Entry> entries=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(int i=1;i<lines.length;i++) {
            String value=stripCr(lines[i]);
            if(value.isBlank()) {
                if(i<lines.length-2)
                    throw new IllegalArgumentException("Unexpected blank bank CSV row");
                continue;
            }
            if(value.chars().anyMatch(c->c<32 || c==127))
                throw new IllegalArgumentException("Invalid bank CSV control character");
            String[] parts=value.split(",",-1);
            if(parts.length!=7)
                throw new IllegalArgumentException("Bank CSV requires exactly 7 columns");
            String id=reference(parts[0]);
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate bank entry ID");
            String settlement=reference(parts[1]);
            LocalDate date;
            try {date=LocalDate.parse(parts[2]);}
            catch(DateTimeParseException invalid) {
                throw new IllegalArgumentException("Invalid bank value date");
            }
            if(date.isBefore(LocalDate.of(2000,1,1))
                ||date.isAfter(LocalDate.of(2100,12,31)))
                throw new IllegalArgumentException("Bank value date out of bounds");
            String currency=parts[3];
            if(!currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("Bank currency must be ISO code");
            BigDecimal credit=money(parts[4],false,6);
            BigDecimal fee=money(parts[5],true,6);
            BigDecimal fx=parts[6].isEmpty()?null:money(parts[6],false,8);
            entries.add(new Entry(i,id,settlement,date,currency,credit,fee,fx));
            if(entries.size()>MAX_ROWS)
                throw new IllegalArgumentException("Too many bank observations");
        }
        if(entries.isEmpty())throw new IllegalArgumentException("No bank observations");
        return List.copyOf(entries);
    }
    private static String stripCr(String row) {
        return row.endsWith("\r")?row.substring(0,row.length()-1):row;
    }
    static String reference(String text) {
        if(text==null||!text.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"))
            throw new IllegalArgumentException("Invalid bank source identifier");
        return text;
    }
    static BigDecimal money(String text,boolean allowZero,int scale) {
        if(text==null||!text.matches("[0-9]{1,14}(?:\\.[0-9]{1,"+scale+"})?"))
            throw new IllegalArgumentException("Invalid bank amount or FX rate");
        BigDecimal number=new BigDecimal(text);
        if(number.signum()<0||(!allowZero&&number.signum()==0))
            throw new IllegalArgumentException("Bank amount or FX rate must be positive");
        return number;
    }
}
