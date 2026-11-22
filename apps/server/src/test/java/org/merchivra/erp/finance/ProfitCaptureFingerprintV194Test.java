package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** V194 deterministic writer/independent verifier contract, no real credentials. */
class ProfitCaptureFingerprintV194Test {
    private static final UUID TENANT=UUID.fromString("00000000-0000-4000-8000-000000000194");
    private static final UUID MARKET=UUID.fromString("00000000-0000-4000-8000-000000000195");
    private static final UUID JOB=UUID.fromString("00000000-0000-4000-8000-000000000196");
    private static final UUID ORDER=UUID.fromString("00000000-0000-4000-8000-000000000197");
    private static final String OLD="a".repeat(64);
    private static BigDecimal d(String value){return new BigDecimal(value);}
    private static ProfitReportJobService.Job job(int total,int eligible,int blocked,
        int processed,String state) {
        return new ProfitReportJobService.Job(JOB.toString(),state,
            "2026-10-01","2026-11-01","USD",total,processed,eligible,blocked,
            0,null,"0",0,"2026-11-01T00:00:00Z",
            "SUCCEEDED".equals(state)?"2026-11-01T01:00:00Z":null,
            true,false,false,false,false);
    }
    private static ProfitCaptureFingerprintV194.Sku sku(String id,String sales) {
        BigDecimal sale=d(sales);
        return new ProfitCaptureFingerprintV194.Sku(id,1,sale,d("-10"),
            d("-5"),d("40"),sale.subtract(d("55")));
    }
    private static ProfitCaptureVerifierV194.FrozenSku frozenSku(
        ProfitCaptureFingerprintV194.Sku s) {
        return new ProfitCaptureVerifierV194.FrozenSku(ORDER,s,1,
            ProfitCaptureFingerprintV194.sku(TENANT,JOB,ORDER,s));
    }
    private static ProfitCaptureVerifierV194.FrozenOrder frozenOrder(
        List<ProfitCaptureVerifierV194.FrozenSku> skus) {
        var original=new ProfitCaptureFingerprintV194.Order("ELIGIBLE",
            "ELIGIBLE_PREVIEW","USD",1,d("100"),d("-10"),d("-5"),
            d("40"),d("45"),OLD);
        var proofs=skus.stream().map(s->
            new ProfitCaptureFingerprintV194.SkuProof(s.values().sku(),
                s.fingerprint())).toList();
        var digest=ProfitCaptureFingerprintV194.order(
            TENANT,MARKET,JOB,ORDER,original,proofs);
        return new ProfitCaptureVerifierV194.FrozenOrder(ORDER,"ELIGIBLE",
            "ELIGIBLE_PREVIEW","USD",1,d("100"),d("-10"),d("-5"),
            d("40"),d("45"),OLD,1,digest);
    }
    private static ProfitCaptureVerifierV194.Summary verify(
        ProfitCaptureVerifierV194.FrozenOrder order,
        List<ProfitCaptureVerifierV194.FrozenSku> skus) {
        return ProfitCaptureVerifierV194.evaluate(TENANT,MARKET,JOB,
            job(1,1,0,1,"SUCCEEDED"),List.of(order),skus);
    }

    @Test void v1IsDeterministicAndPreservesSkuOrderIndependentMembership() {
        var a=frozenSku(new ProfitCaptureFingerprintV194.Sku(
            "中文-SKU",1,d("60"),d("-6"),d("-3"),d("24"),d("27")));
        var b=frozenSku(new ProfitCaptureFingerprintV194.Sku(
            "=CSV",1,d("40"),d("-4"),d("-2"),d("16"),d("18")));
        var frozen=frozenOrder(List.of(a,b));
        assertEquals(frozen.fingerprint(),frozenOrder(List.of(b,a)).fingerprint());
        var check=verify(frozen,List.of(b,a));
        assertEquals(1,check.locallyVerifiedOrders());
        assertTrue(check.localEvidenceComplete());
        assertTrue(check.profitBaselineAllowed());
        assertTrue(check.blockerCodes().contains(
            "EXTERNAL_FINANCES_ADS_BANK_AND_REFUND_COGS_UNVERIFIED"));
    }

    @Test void legacyOnlyHashNeverBecomesNewEvidence() {
        var sku=frozenSku(sku("A","100"));
        var order=frozenOrder(List.of(sku));
        var historical=new ProfitCaptureVerifierV194.FrozenOrder(
            ORDER,order.state(),order.readiness(),order.currency(),
            order.reviewedEvents(),order.sales(),order.amazonFees(),order.fbaFees(),
            order.cogs(),order.contribution(),order.legacySha(),null,null);
        var result=verify(historical,List.of(new ProfitCaptureVerifierV194.FrozenSku(
            ORDER,sku.values(),null,null)));
        assertEquals(1,result.historicalLegacyOrders());
        assertEquals(0,result.locallyVerifiedOrders());
        assertFalse(result.profitBaselineAllowed());
        assertTrue(result.blockerCodes().contains("V194_HISTORICAL_UNVERSIONED_EXTRACT"));
    }

    @Test void independentlyRecomputedFingerprintDetectsAmountsAndMissingSku() {
        var item=frozenSku(sku("A","100"));
        var order=frozenOrder(List.of(item));
        assertTrue(verify(order,List.of(item)).profitBaselineAllowed());
        var altered=new ProfitCaptureVerifierV194.FrozenSku(ORDER,
            sku("A","101"),1,item.fingerprint());
        assertTrue(verify(order,List.of(altered)).fingerprintMismatchOrders()>0);
        assertTrue(verify(order,List.of()).fingerprintMismatchOrders()>0);
        var otherTenantFingerprint=ProfitCaptureFingerprintV194.sku(
            UUID.randomUUID(),JOB,ORDER,item.values());
        assertNotEquals(item.fingerprint(),otherTenantFingerprint);
        var otherJobFingerprint=ProfitCaptureFingerprintV194.sku(
            TENANT,UUID.randomUUID(),ORDER,item.values());
        assertNotEquals(item.fingerprint(),otherJobFingerprint);
    }

    @Test void interruptedAndBlockedCohortsCannotYieldProfitBaseline() {
        var item=frozenSku(sku("A","100"));
        var order=frozenOrder(List.of(item));
        var pending=ProfitCaptureVerifierV194.evaluate(TENANT,MARKET,JOB,
            job(2,1,0,1,"READY"),List.of(order,
                new ProfitCaptureVerifierV194.FrozenOrder(
                    UUID.randomUUID(),"PENDING",null,null,0,null,null,
                    null,null,null,null,null,null)),List.of(item));
        assertFalse(pending.profitBaselineAllowed());
        assertEquals(1,pending.pendingOrders());
        var blockedInfo=new ProfitCaptureFingerprintV194.Order("BLOCKED",
            "SOURCE_EVIDENCE_UNAVAILABLE","USD",0,null,null,null,null,null,OLD);
        String blockedHash=ProfitCaptureFingerprintV194.order(
            TENANT,MARKET,JOB,ORDER,blockedInfo,List.of());
        var blocked=new ProfitCaptureVerifierV194.FrozenOrder(
            ORDER,"BLOCKED","SOURCE_EVIDENCE_UNAVAILABLE",
            "USD",0,null,null,null,null,null,OLD,1,blockedHash);
        var blockedSummary=ProfitCaptureVerifierV194.evaluate(TENANT,MARKET,JOB,
            job(1,0,1,1,"SUCCEEDED"),List.of(blocked),List.of());
        assertEquals(1,blockedSummary.locallyVerifiedOrders());
        assertFalse(blockedSummary.profitBaselineAllowed());
    }

    @Test void numericCanonicalizationRejectsRoundingAndAmbiguousFields() {
        var canonical=sku("ABC","100");
        var equivalent=new ProfitCaptureFingerprintV194.Sku(
            "ABC",1,d("100.000000"),d("-10.000000"),d("-5.000000"),
            d("40.000000"),d("45.000000"));
        assertEquals(ProfitCaptureFingerprintV194.sku(TENANT,JOB,ORDER,canonical),
            ProfitCaptureFingerprintV194.sku(TENANT,JOB,ORDER,equivalent));
        assertThrows(ArithmeticException.class,()->
            ProfitCaptureFingerprintV194.sku(TENANT,JOB,ORDER,
                sku("ABC","100.0000001")));
        assertNotEquals(ProfitCaptureFingerprintV194.sku(TENANT,JOB,ORDER,
                sku("ABC|X","100")),
            ProfitCaptureFingerprintV194.sku(TENANT,JOB,ORDER,
                sku("ABC","100")));
    }

    @Test void v104MigrationPreservesHistoricalNullAndV103Guards() throws Exception {
        String migration=Files.readString(Path.of(
            "src/main/resources/db/migration/V104__versioned_profit_capture_fingerprint.sql"));
        assertTrue(migration.contains("capture_version_v194 SMALLINT"));
        assertTrue(migration.contains("capture_sha256_v194 CHAR(64)"));
        assertTrue(migration.contains("capture_version_v194 IS NULL"));
        assertFalse(migration.contains("UPDATE finance_profit_report_order"));
        assertFalse(migration.contains("CREATE EXTENSION"));
        var source=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/ProfitReportJobService.java"));
        assertTrue(source.contains("capture_version_v194=1,capture_sha256_v194=?"));
        assertTrue(source.contains("capture_version_v194,capture_sha256_v194"));
        assertTrue(source.contains("captures.verify(tenant,mp,id,job)"));
        assertTrue(Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/PeriodProfitWaterfallServiceV188.java"))
            .contains("reports.captureVerification(tenant,marketplace,reportJobId)"));
    }
}
