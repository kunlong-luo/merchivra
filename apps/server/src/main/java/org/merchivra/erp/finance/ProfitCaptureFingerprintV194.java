package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * V194 locally generated, versioned evidence over the frozen V62 order and
 * SKU rows. It does NOT authenticate Amazon, Ads, bank or refund COGS.
 *
 * Every field is length-prefixed UTF-8 with an explicit NULL (-1) sentinel;
 * NUMERIC(20,6) values use a fixed six-decimal representation. Sorted SKU
 * fingerprints and their count bind membership, not just the parent totals.
 */
public final class ProfitCaptureFingerprintV194 {
    public static final int VERSION=1;
    private ProfitCaptureFingerprintV194() {}

    public record Sku(String sku,int units,BigDecimal sales,
        BigDecimal amazonFees,BigDecimal fbaFees,BigDecimal cogs,
        BigDecimal contribution) {}
    public record Order(String state,String readiness,String currency,
        int reviewedEvents,BigDecimal sales,BigDecimal amazonFees,
        BigDecimal fbaFees,BigDecimal cogs,BigDecimal contribution,
        String legacyExtractSha256) {}
    public record SkuProof(String sku,String fingerprint) {}

    private static String amount(BigDecimal value) {
        return value==null?null:value.setScale(6,RoundingMode.UNNECESSARY)
            .toPlainString();
    }
    private static String digest(String kind,List<String> fields) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            add(hash,"MERCHIVRA_FROZEN_PROFIT_EVIDENCE");
            add(hash,"V1");
            add(hash,kind);
            for(String field:fields)add(hash,field);
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void add(MessageDigest hash,String value) {
        if(value==null) {
            hash.update(ByteBuffer.allocate(4).putInt(-1).array());
        }else {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            hash.update(bytes);
        }
    }
    public static String sku(UUID tenant,UUID job,UUID order,Sku s) {
        return digest("SKU",List.of(tenant.toString(),job.toString(),
            order.toString(),s.sku(),Integer.toString(s.units()),
            amount(s.sales()),amount(s.amazonFees()),amount(s.fbaFees()),
            amount(s.cogs()),amount(s.contribution())));
    }
    public static String order(UUID tenant,UUID marketplace,UUID job,
        UUID order,Order o,List<SkuProof> evidence) {
        var parts=new ArrayList<String>();
        // Null financial fields are legal for blocked orders, but never
        // collapse into empty strings or fabricated zeros.
        addNullable(parts,tenant.toString(),marketplace.toString(),
            job.toString(),order.toString(),o.state(),o.readiness(),
            o.currency(),Integer.toString(o.reviewedEvents()),
            amount(o.sales()),amount(o.amazonFees()),amount(o.fbaFees()),
            amount(o.cogs()),amount(o.contribution()),o.legacyExtractSha256());
        var sorted=new ArrayList<>(evidence);
        sorted.sort(Comparator.comparing(SkuProof::sku));
        parts.add(Integer.toString(sorted.size()));
        String previous=null;
        for(var proof:sorted) {
            if(proof.sku()==null || proof.sku().equals(previous)
                ||proof.fingerprint()==null)
                throw new IllegalArgumentException("Duplicate or incomplete frozen SKU proof");
            parts.add(proof.sku());
            parts.add(proof.fingerprint());
            previous=proof.sku();
        }
        return digest("ORDER",parts);
    }
    private static void addNullable(List<String> into,String... fields) {
        // List.of rejects null values; frozen BLOCKED amounts are intentionally null.
        for(String field:fields)into.add(field);
    }
}
