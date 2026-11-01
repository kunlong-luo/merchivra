package org.merchivra.erp.finance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Narrow, fail-closed TSV importer for settlement report data only.
 * Does not trust arbitrary "Amazon report" CSV layouts and never stores raw
 * documents or their unknown columns (possibly containing customer PII).
 */
public final class SettlementReportParser {
    public static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
    public static final int MAX_UNCOMPRESSED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_LINES = 10000;

    public record Line(int number, String transactionType, String amountType,
        String amountDescription, String orderId, BigDecimal amount, String currency) {}
    public record Parsed(String settlementId, String currency,
        BigDecimal declaredNet, List<Line> lines) {}

    private SettlementReportParser() {}

    public static Parsed parse(byte[] document, boolean gzip) {
        if (document == null || document.length == 0
            || document.length > MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Report document outside size bounds");
        byte[] bytes = gzip ? gunzip(document) : document;
        if (bytes.length > MAX_UNCOMPRESSED_BYTES)
            throw new IllegalArgumentException("Decompressed report exceeds limit");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Report is not valid UTF-8");
        }
        if (text.startsWith("\uFEFF")) text=text.substring(1);
        if (text.indexOf('\0') >= 0 || text.indexOf('"') >= 0)
            throw new IllegalArgumentException("Unsupported report control or quoting");
        String[] physical=text.split("\n",-1);
        if (physical.length > MAX_LINES + 5)
            throw new IllegalArgumentException("Too many report lines");
        int offset=0;
        while (offset<physical.length && physical[offset].isBlank()) offset++;
        if(offset>=physical.length)throw new IllegalArgumentException("Empty report");
        String[] first=columns(physical[offset]);
        Map<String,Integer> firstIndex=headers(first);
        String settlementId=null,currency=null;
        BigDecimal declared=null;
        // Some Amazon settlement versions lead with a metadata header/value
        // followed by the actual transaction header and data rows.
        if (firstIndex.containsKey("total-amount") &&
            !firstIndex.containsKey("transaction-type")) {
            if (++offset >= physical.length)
                throw new IllegalArgumentException("Report summary row missing");
            String[] values=columns(physical[offset++]);
            requireWidth(first,values);
            settlementId=identifier(field(values,firstIndex,"settlement-id"),160);
            currency=currency(field(values,firstIndex,"currency"));
            String reported=field(values,firstIndex,"total-amount");
            declared=reported.isBlank()?null:amount(reported);
            if(offset>=physical.length)throw new IllegalArgumentException("Detail header missing");
            first=columns(physical[offset]);
            firstIndex=headers(first);
        }
        if (!firstIndex.containsKey("amount")
            || !firstIndex.containsKey("transaction-type")
            || !firstIndex.containsKey("settlement-id"))
            throw new IllegalArgumentException("Required settlement detail columns missing");
        List<Line> lines=new ArrayList<>();
        for (int p=offset+1;p<physical.length;p++) {
            if (physical[p].isBlank())continue;
            String[] row=columns(physical[p]);
            requireWidth(first,row);
            String ref=identifier(field(row,firstIndex,"settlement-id"),160);
            if (settlementId==null)settlementId=ref;
            if (!settlementId.equals(ref))
                throw new IllegalArgumentException("Mixed settlement references");
            String rowCurrency=field(row,firstIndex,"currency");
            if(!rowCurrency.isBlank()) {
                rowCurrency=currency(rowCurrency);
                if(currency==null)currency=rowCurrency;
                if(!currency.equals(rowCurrency))
                    throw new IllegalArgumentException("Mixed currency in report");
            }
            String type=classification(field(row,firstIndex,"transaction-type"),100);
            String amountType=classification(field(row,firstIndex,"amount-type"),100);
            String detail=classification(field(row,firstIndex,"amount-description"),160);
            String order=field(row,firstIndex,"order-id");
            if(!order.isBlank())order=identifier(order,160);
            lines.add(new Line(lines.size()+1,type,amountType,detail,
                order.isBlank()?null:order,amount(field(row,firstIndex,"amount")),
                rowCurrency.isBlank()?currency:rowCurrency));
            if(lines.size()>MAX_LINES)throw new IllegalArgumentException("Too many report rows");
        }
        if(lines.isEmpty() || currency==null)
            throw new IllegalArgumentException("Report missing lines or original currency");
        return new Parsed(settlementId,currency,declared,List.copyOf(lines));
    }

    private static byte[] gunzip(byte[] bytes) {
        try (var in=new GZIPInputStream(new ByteArrayInputStream(bytes));
             var out=new ByteArrayOutputStream()) {
            byte[] part=new byte[8192];
            int n;
            while((n=in.read(part))!=-1) {
                if(out.size()+n>MAX_UNCOMPRESSED_BYTES)
                    throw new IllegalArgumentException("GZIP expansion exceeds report bound");
                out.write(part,0,n);
            }
            return out.toByteArray();
        } catch(IOException ex) {
            throw new IllegalArgumentException("Invalid GZIP settlement report");
        }
    }

    private static String[] columns(String line) {
        String value=line.endsWith("\r")?line.substring(0,line.length()-1):line;
        return value.split("\t",-1);
    }
    private static Map<String,Integer> headers(String[] cols) {
        Map<String,Integer> result=new HashMap<>();
        for(int i=0;i<cols.length;i++) {
            String h=cols[i].trim().toLowerCase(Locale.ROOT);
            if(h.isEmpty() || result.putIfAbsent(h,i)!=null)
                throw new IllegalArgumentException("Blank or repeated report column");
        }
        return result;
    }
    private static void requireWidth(String[] header,String[] row) {
        if(row.length!=header.length)throw new IllegalArgumentException("Malformed report row");
    }
    private static String field(String[] row,Map<String,Integer> headers,String name) {
        Integer index=headers.get(name);
        return index==null?"":row[index].trim();
    }
    private static String currency(String s) {
        if(!s.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid report currency");
        return s;
    }
    private static BigDecimal amount(String s) {
        if(!s.matches("-?[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw new IllegalArgumentException("Invalid signed report amount");
        return new BigDecimal(s);
    }
    private static String identifier(String s,int max) {
        if(s.length()>max || !s.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*"))
            throw new IllegalArgumentException("Invalid report identifier");
        return s;
    }
    private static String classification(String s,int max) {
        if(s.length()>max || !s.matches("[A-Za-z0-9 _./+()\\-]*"))
            throw new IllegalArgumentException("Invalid report classification");
        return s.isBlank()?null:s;
    }
}
