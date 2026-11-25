package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Content-addressed, deterministic mapping-only version. No source or approval claim. */
final class SettlementCategoryMappingV274 {
    private SettlementCategoryMappingV274() {}
    static final String HEADER="V274_REPORT_MAPPING";
    record Canonical(String payload,String sha256,List<SettlementCategoryPreviewV273.Mapping> mappings) {}
    private static String field(String value) {return value==null?"~":value;}
    private static String decodeField(String value) {return "~".equals(value)?null:value;}
    static Canonical canonical(List<SettlementCategoryPreviewV273.Mapping> input) {
        var valid=SettlementCategoryPreviewV273.validate(input);
        var rows=valid.entrySet().stream().map(e->field(e.getKey().transactionType())+"|"+
            field(e.getKey().amountType())+"|"+field(e.getKey().amountDescription())+"|"+e.getValue())
            .sorted().toList();
        String payload=HEADER+"\n"+String.join("\n",rows);
        if(payload.length()>85000)throw new IllegalArgumentException("Mapping too large");
        return new Canonical(payload,digest(payload),decode(payload));
    }
    static Canonical stored(String payload,String sha) {
        if(payload==null||sha==null||payload.length()>85000||!digest(payload).equals(sha))
            throw new IllegalArgumentException("Stored mapping digest mismatch");
        var canonical=canonical(decode(payload));
        if(!canonical.payload().equals(payload)||!canonical.sha256().equals(sha))
            throw new IllegalArgumentException("Stored mapping is not canonical");
        return canonical;
    }
    private static List<SettlementCategoryPreviewV273.Mapping> decode(String payload) {
        String[] lines=payload.split("\n",-1);
        if(lines.length<2||lines.length>201||!HEADER.equals(lines[0]))
            throw new IllegalArgumentException("Mapping format");
        var out=new ArrayList<SettlementCategoryPreviewV273.Mapping>();
        for(int i=1;i<lines.length;i++) {
            var parts=lines[i].split("\\|",-1);
            if(parts.length!=4)throw new IllegalArgumentException("Mapping row");
            out.add(new SettlementCategoryPreviewV273.Mapping(
                new SettlementCategoryPreviewV273.ReportType(decodeField(parts[0]),
                    decodeField(parts[1]),decodeField(parts[2])),parts[3]));
        }
        SettlementCategoryPreviewV273.validate(out);
        return List.copyOf(out);
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
