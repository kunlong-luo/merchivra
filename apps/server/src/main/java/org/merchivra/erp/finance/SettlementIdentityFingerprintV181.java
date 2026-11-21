package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Stable, tenant-scoped fingerprint of the already-frozen V102 snapshot.
 * This is a deterministic digest for local comparison, NOT an Amazon signature,
 * bank confirmation or independent verification of the original report bytes.
 *
 * Canonical V1 format: each UTF-8 field is a signed, 4-byte big-endian length
 * followed by the exact bytes; -1 denotes NULL (different from empty string).
 * Field order is fixed. UUIDs are lowercase canonical strings, amounts are
 * the persisted NUMERIC(20,6) plain string and capturedAt is an ISO UTC instant.
 */
final class SettlementIdentityFingerprintV181 {
    static final String VERSION = "MERCHIVRA_SETTLEMENT_LINE_IDENTITY_V1";
    private SettlementIdentityFingerprintV181() {}

    static String digest(UUID tenant,UUID marketplace,UUID source,
        CostSettlementLineEvidenceService.CompositeIdentity evidence) {
        if(evidence==null || !"FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED"
            .equals(evidence.captureStatus()))
            throw new IllegalArgumentException(
                "Historical or missing settlement identity has no fingerprint");
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{
                VERSION,tenant.toString(),marketplace.toString(),source.toString(),
                evidence.linkId(),evidence.reportId(),
                evidence.lineNumber().toString(),evidence.amazonReportId(),
                evidence.reportDocumentId(),evidence.reportDocumentSha256(),
                evidence.transactionType(),evidence.amountType(),
                evidence.amountDescription(),evidence.externalOrderId(),
                evidence.lineSignedAmount(),evidence.currency(),
                evidence.recordedBy(),evidence.capturedAt()
            })append(digest,value);
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 unavailable",e);
        }
    }

    private static void append(MessageDigest digest,String value) {
        if(value==null) {
            digest.update(new byte[]{-1,-1,-1,-1});
            return;
        }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        int size=bytes.length;
        digest.update((byte)(size>>>24));
        digest.update((byte)(size>>>16));
        digest.update((byte)(size>>>8));
        digest.update((byte)size);
        digest.update(bytes);
    }
}
