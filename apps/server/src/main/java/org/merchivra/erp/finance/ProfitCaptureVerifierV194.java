package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independently replays V194 fingerprints using ONLY persisted V62 rows,
 * never the in-memory values captured by the writer or current SP-API data.
 * Historical V62 extract_sha256 without V194 fields remains LEGACY_ONLY.
 */
public final class ProfitCaptureVerifierV194 {
    private static final int MAX_SKU_ROWS=100000;
    private final JdbcTemplate jdbc;
    ProfitCaptureVerifierV194(JdbcTemplate jdbc){this.jdbc=jdbc;}

    record FrozenOrder(UUID orderId,String state,String readiness,
        String currency,int reviewedEvents,BigDecimal sales,BigDecimal amazonFees,
        BigDecimal fbaFees,BigDecimal cogs,BigDecimal contribution,
        String legacySha,Integer version,String fingerprint) {}
    record FrozenSku(UUID orderId,ProfitCaptureFingerprintV194.Sku values,
        Integer version,String fingerprint) {}
    public record Summary(int capturedOrders,int locallyVerifiedOrders,
        int historicalLegacyOrders,int fingerprintMismatchOrders,
        int pendingOrders,boolean localEvidenceComplete,
        boolean profitBaselineAllowed,List<String> blockerCodes) {}

    Summary verify(UUID tenant,UUID marketplace,UUID jobId,
        ProfitReportJobService.Job job) {
        var orders=jdbc.query("""
            SELECT o.order_id,o.state,o.readiness,o.currency_code,o.reviewed_events,
              o.signed_sales,o.signed_amazon_fees,o.signed_fba_fees,
              o.provisional_cogs,o.estimated_contribution,o.extract_sha256,
              o.capture_version_v194,o.capture_sha256_v194
            FROM finance_profit_report_order o
            JOIN finance_profit_report_job j
              ON j.tenant_id=o.tenant_id AND j.id=o.job_id
            WHERE j.tenant_id=? AND j.seller_marketplace_id=? AND j.id=?
            ORDER BY o.cohort_position
            """,(r,i)->new FrozenOrder(r.getObject("order_id",UUID.class),
                r.getString("state"),r.getString("readiness"),
                r.getString("currency_code")==null?null:r.getString("currency_code").trim(),
                r.getInt("reviewed_events"),r.getBigDecimal("signed_sales"),
                r.getBigDecimal("signed_amazon_fees"),r.getBigDecimal("signed_fba_fees"),
                r.getBigDecimal("provisional_cogs"),r.getBigDecimal("estimated_contribution"),
                r.getString("extract_sha256"),
                r.getObject("capture_version_v194")==null?null:r.getInt("capture_version_v194"),
                r.getString("capture_sha256_v194")),tenant,marketplace,jobId);
        var skuRows=jdbc.query("""
            SELECT s.order_id,s.internal_sku,s.units,s.signed_sales,
              s.signed_amazon_fees,s.signed_fba_fees,s.provisional_cogs,
              s.estimated_contribution,s.capture_version_v194,s.capture_sha256_v194
            FROM finance_profit_report_sku s
            JOIN finance_profit_report_job j
              ON j.tenant_id=s.tenant_id AND j.id=s.job_id
            WHERE j.tenant_id=? AND j.seller_marketplace_id=? AND j.id=?
            ORDER BY s.order_id,s.internal_sku LIMIT 100001
            """,(r,i)->new FrozenSku(r.getObject("order_id",UUID.class),
                new ProfitCaptureFingerprintV194.Sku(r.getString("internal_sku"),
                    r.getInt("units"),r.getBigDecimal("signed_sales"),
                    r.getBigDecimal("signed_amazon_fees"),r.getBigDecimal("signed_fba_fees"),
                    r.getBigDecimal("provisional_cogs"),
                    r.getBigDecimal("estimated_contribution")),
                r.getObject("capture_version_v194")==null?null:r.getInt("capture_version_v194"),
                r.getString("capture_sha256_v194")),tenant,marketplace,jobId);
        if(skuRows.size()>MAX_SKU_ROWS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "V194 frozen SKU verification limit exceeded; no profit calculation");
        return evaluate(tenant,marketplace,jobId,job,orders,skuRows);
    }
    static Summary evaluate(UUID tenant,UUID marketplace,UUID jobId,
        ProfitReportJobService.Job job,List<FrozenOrder> orders,List<FrozenSku> skus) {
        var byOrder=new HashMap<UUID,List<FrozenSku>>();
        for(var sku:skus)byOrder.computeIfAbsent(sku.orderId(),
            ignored->new ArrayList<>()).add(sku);
        int verified=0,legacy=0,mismatches=0,pending=0,captured=0;
        int eligible=0,blocked=0;
        for(var row:orders) {
            var item=byOrder.remove(row.orderId());
            if(item==null)item=List.of();
            if("PENDING".equals(row.state())) {
                pending++;
                if(!item.isEmpty()||row.version()!=null||row.fingerprint()!=null)
                    mismatches++;
                continue;
            }
            captured++;
            if("ELIGIBLE".equals(row.state()))eligible++;
            else if("BLOCKED".equals(row.state()))blocked++;
            else {mismatches++;continue;}
            if(row.version()==null&&row.fingerprint()==null) {
                legacy++;
                continue; // The old unversioned hash MUST NOT be promoted.
            }
            if(!Integer.valueOf(ProfitCaptureFingerprintV194.VERSION).equals(row.version())
                ||row.fingerprint()==null) {mismatches++;continue;}
            try {
                var proofs=new ArrayList<ProfitCaptureFingerprintV194.SkuProof>();
                BigDecimal sales=BigDecimal.ZERO,amazon=BigDecimal.ZERO,
                    fba=BigDecimal.ZERO,cogs=BigDecimal.ZERO,contribution=BigDecimal.ZERO;
                boolean valid=true;
                for(var s:item) {
                    if(!Integer.valueOf(ProfitCaptureFingerprintV194.VERSION)
                        .equals(s.version())) {valid=false;break;}
                    String calculated=ProfitCaptureFingerprintV194.sku(
                        tenant,jobId,row.orderId(),s.values());
                    if(!calculated.equals(s.fingerprint())) {valid=false;break;}
                    proofs.add(new ProfitCaptureFingerprintV194.SkuProof(
                        s.values().sku(),calculated));
                    sales=sales.add(s.values().sales());
                    amazon=amazon.add(s.values().amazonFees());
                    fba=fba.add(s.values().fbaFees());
                    cogs=cogs.add(s.values().cogs());
                    contribution=contribution.add(s.values().contribution());
                }
                if("ELIGIBLE".equals(row.state())) {
                    valid&=!item.isEmpty()&&job.currency().equals(row.currency())
                        &&row.sales()!=null&&row.amazonFees()!=null
                        &&row.fbaFees()!=null&&row.cogs()!=null
                        &&row.contribution()!=null
                        &&sales.compareTo(row.sales())==0
                        &&amazon.compareTo(row.amazonFees())==0
                        &&fba.compareTo(row.fbaFees())==0
                        &&cogs.compareTo(row.cogs())==0
                        &&contribution.compareTo(row.contribution())==0
                        &&row.sales().add(row.amazonFees()).add(row.fbaFees())
                            .subtract(row.cogs()).compareTo(row.contribution())==0;
                }else {
                    valid&=item.isEmpty()&&row.sales()==null&&row.amazonFees()==null
                        &&row.fbaFees()==null&&row.cogs()==null
                        &&row.contribution()==null;
                }
                String orderHash=ProfitCaptureFingerprintV194.order(
                    tenant,marketplace,jobId,row.orderId(),
                    new ProfitCaptureFingerprintV194.Order(
                        row.state(),row.readiness(),row.currency(),row.reviewedEvents(),
                        row.sales(),row.amazonFees(),row.fbaFees(),row.cogs(),
                        row.contribution(),row.legacySha()),proofs);
                if(valid&&orderHash.equals(row.fingerprint()))verified++;
                else mismatches++;
            }catch(RuntimeException malformed){mismatches++;}
        }
        // Includes orphan SKU rows, forged cohort count or mismatched status
        // totals. Never trust locally consistent sums if cohort membership is wrong.
        if(!byOrder.isEmpty()||orders.size()!=job.totalOrders()
            ||eligible!=job.eligibleOrders()||blocked!=job.blockedOrders()
            ||captured!=job.processedOrders())mismatches++;
        boolean complete=mismatches==0&&legacy==0&&pending==0
            &&verified==orders.size()&&orders.size()==job.totalOrders();
        boolean allowed=complete&&"SUCCEEDED".equals(job.state())
            &&job.totalOrders()>0&&blocked==0&&eligible==job.totalOrders();
        var codes=new ArrayList<String>();
        if(legacy>0)codes.add("V194_HISTORICAL_UNVERSIONED_EXTRACT");
        if(mismatches>0)codes.add("V194_FROZEN_FINGERPRINT_MISMATCH");
        if(pending>0)codes.add("V194_PENDING_ORDER_CAPTURE");
        if(!"SUCCEEDED".equals(job.state()))codes.add("V194_COHORT_NOT_COMPLETED");
        if(blocked>0)codes.add("V194_ORDER_SOURCE_EVIDENCE_BLOCKED");
        if(job.totalOrders()==0)codes.add("V194_NO_ELIGIBLE_FINANCIAL_BASELINE");
        if(!allowed)codes.add("V194_PROFIT_ARITHMETIC_BLOCKED");
        // Even complete local fingerprints never attest real net profit.
        codes.add("EXTERNAL_FINANCES_ADS_BANK_AND_REFUND_COGS_UNVERIFIED");
        return new Summary(captured,verified,legacy,mismatches,pending,
            complete,allowed,List.copyOf(codes));
    }
}
