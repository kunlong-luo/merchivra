package org.merchivra.erp.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V187 entire-period read-only V165/V168 collision exposure.
 * Exact LOCAL keys and shared imported lines are hypotheses, NOT confirmed
 * duplicate debits, external bill verification, expense eligibility or profit.
 * A 20-row example cap must NEVER cap the entire-period barrier counters.
 */
@Service
public class CostCrossSourceRiskServiceV187 {
    private final JdbcTemplate jdbc;
    public CostCrossSourceRiskServiceV187(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Example(String sourceId,String businessDate,
        List<String> localSignalCodes,boolean sharedSettlementLineClaim,
        String status,boolean duplicateDebitVerified,
        boolean expenseDeductionAllowed) {}
    public record Report(String marketplaceId,String periodStart,String periodEndExclusive,
        String dateBasis,long sourceCount,long potentialIdentityOverlapSourceCount,
        long sharedSettlementLineSourceCount,long anyLocalCollisionSourceCount,
        long noLocalMatchSourceCount,List<Example> examples,
        boolean examplesTruncated,List<String> blockerCodes,
        boolean duplicateDebitVerified,boolean externalChargeVerified,
        boolean refundCogsReversalVerified,boolean accountingPolicyApproved,
        boolean bankReconciled,boolean netProfitEligible,String realizedNetProfit) {}
    record Counts(long sources,long identity,long shared,long any,long noMatch) {}
    record Flags(UUID id,LocalDate date,List<Boolean> signals,boolean shared) {}

    /**
     * Exactly the eight V165 source-namespace probes + the V168 same-imported
     * settlement-line link probe. Candidates may be outside the chosen date
     * range, but must be in the SAME tenant and Marketplace. No raw invoice
     * keys, report documents or other source identities are exposed.
     */
    private static final String FLAGS_SQL="""
        WITH source_flags AS (
          SELECT s.id,s.business_date,
            EXISTS(SELECT 1 FROM finance_cost_source_v91 c
              WHERE c.tenant_id=s.tenant_id
                AND c.seller_marketplace_id=s.seller_marketplace_id
                AND c.id<>s.id
                AND c.source_document_sha256=s.source_document_sha256) AS manual_doc,
            EXISTS(SELECT 1 FROM finance_ad_spend_reference a
              WHERE a.tenant_id=s.tenant_id
                AND a.seller_marketplace_id=s.seller_marketplace_id
                AND a.source_key=s.source_key) AS ads_key,
            EXISTS(SELECT 1 FROM ads_report_import a
              WHERE a.tenant_id=s.tenant_id
                AND a.seller_marketplace_id=s.seller_marketplace_id
                AND a.report_reference=s.source_key
                AND a.state<>'REJECTED') AS ads_report_key,
            EXISTS(SELECT 1 FROM ads_report_import a
              WHERE a.tenant_id=s.tenant_id
                AND a.seller_marketplace_id=s.seller_marketplace_id
                AND a.report_sha256=s.source_document_sha256
                AND a.state<>'REJECTED') AS ads_report_doc,
            EXISTS(SELECT 1 FROM finance_source_event f
              WHERE f.tenant_id=s.tenant_id
                AND f.seller_marketplace_id=s.seller_marketplace_id
                AND f.source_family='SPAPI_FINANCES'
                AND f.source_event_key=s.source_key) AS finances_key,
            EXISTS(SELECT 1 FROM spapi_settlement_report r
              WHERE r.tenant_id=s.tenant_id
                AND r.seller_marketplace_id=s.seller_marketplace_id
                AND (r.amazon_report_id=s.source_key
                  OR r.report_document_id=s.source_key
                  OR r.settlement_reference=s.source_key)) AS spapi_report_key,
            EXISTS(SELECT 1 FROM spapi_settlement_report r
              WHERE r.tenant_id=s.tenant_id
                AND r.seller_marketplace_id=s.seller_marketplace_id
                AND r.document_sha256=s.source_document_sha256) AS spapi_report_doc,
            EXISTS(SELECT 1 FROM finance_settlement_statement t
              WHERE t.tenant_id=s.tenant_id
                AND t.seller_marketplace_id=s.seller_marketplace_id
                AND t.statement_ref=s.source_key) AS statement_key,
            EXISTS(SELECT 1 FROM finance_cost_settlement_line_link_v94 x
              WHERE x.tenant_id=s.tenant_id
                AND x.seller_marketplace_id=s.seller_marketplace_id
                AND x.source_id=s.id
                AND EXISTS(
                  SELECT 1 FROM finance_cost_settlement_line_link_v94 y
                  WHERE y.tenant_id=x.tenant_id
                    AND y.seller_marketplace_id=x.seller_marketplace_id
                    AND y.report_id=x.report_id AND y.line_number=x.line_number
                    AND y.source_id<>x.source_id)) AS shared_line
          FROM finance_cost_source_v91 s
          JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
          JOIN seller_marketplace m ON m.tenant_id=s.tenant_id
            AND m.id=s.seller_marketplace_id
          WHERE s.tenant_id=? AND s.seller_marketplace_id=?
            AND s.business_date>=? AND s.business_date<?
        ), collision_flags AS (
          SELECT *, (manual_doc OR ads_key OR ads_report_key OR ads_report_doc
            OR finances_key OR spapi_report_key OR spapi_report_doc
            OR statement_key) AS identity_overlap
          FROM source_flags
        )
        """;
    private static final String AGGREGATE_SQL=FLAGS_SQL+"""
        SELECT count(*) AS sources,
          count(*) FILTER(WHERE identity_overlap) AS identity_count,
          count(*) FILTER(WHERE shared_line) AS shared_count,
          count(*) FILTER(WHERE identity_overlap OR shared_line) AS any_count,
          count(*) FILTER(WHERE NOT identity_overlap AND NOT shared_line) AS none_count
        FROM collision_flags
        """;
    private static final String EXAMPLE_SQL=FLAGS_SQL+"""
        SELECT id,business_date,manual_doc,ads_key,ads_report_key,ads_report_doc,
          finances_key,spapi_report_key,spapi_report_doc,statement_key,shared_line
        FROM collision_flags
        WHERE identity_overlap OR shared_line
        ORDER BY business_date DESC,id DESC LIMIT 21
        """;

    private static Flags flags(ResultSet r,int ignored)throws SQLException {
        return new Flags(r.getObject(1,UUID.class),r.getObject(2,LocalDate.class),
            List.of(r.getBoolean(3),r.getBoolean(4),r.getBoolean(5),
                r.getBoolean(6),r.getBoolean(7),r.getBoolean(8),
                r.getBoolean(9),r.getBoolean(10)),r.getBoolean(11));
    }
    static Report summarize(UUID marketplace,LocalDate start,LocalDate end,
        Counts counts,List<Flags> sample) {
        CostOwnershipBridgeServiceV186.validate(start,end);
        if(marketplace==null||counts==null||sample==null
            ||counts.sources()<0||counts.identity()<0||counts.shared()<0
            ||counts.any()<0||counts.noMatch()<0
            ||counts.identity()>counts.any()||counts.shared()>counts.any()
            ||counts.any()>counts.sources()
            ||counts.any()+counts.noMatch()!=counts.sources()
            ||sample.size()>21
            ||sample.stream().anyMatch(f->f==null||f.id()==null
                ||f.date()==null||f.date().isBefore(start)
                ||!f.date().isBefore(end)||f.signals()==null
                ||f.signals().size()!=CostIdentityReconciliationService.SIGNALS.size()
                ||f.signals().stream().anyMatch(x->x==null)
                ||!f.shared()&&!f.signals().contains(true))
            ||counts.any()<Math.min(20,sample.size())
            ||counts.any()==0&&!sample.isEmpty()
            ||counts.any()>0&&sample.isEmpty()
            ||sample.size()!=Math.min(21,counts.any()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Full-period collision evidence cannot be reconciled");
        var examples=new ArrayList<Example>();
        var seen=new java.util.HashSet<UUID>();
        long sampleIdentity=0,sampleShared=0;
        for(var f:sample.subList(0,Math.min(20,sample.size()))) {
            if(!seen.add(f.id()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicated source in collision evidence sample");
            var matches=new ArrayList<String>();
            for(int i=0;i<CostIdentityReconciliationService.SIGNALS.size();i++)
                if(f.signals().get(i))
                    matches.add(CostIdentityReconciliationService.SIGNALS.get(i));
            if(!matches.isEmpty())sampleIdentity++;
            if(f.shared())sampleShared++;
            examples.add(new Example(f.id().toString(),f.date().toString(),
                List.copyOf(matches),f.shared(),
                "POTENTIAL_LOCAL_COLLISION_HOLD_UNVERIFIED",false,false));
        }
        if(sampleIdentity>counts.identity()||sampleShared>counts.shared())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Collision sample exceeds full-period counts");
        var blockers=new ArrayList<String>();
        blockers.add("INDEPENDENT_EXTERNAL_EXPENSE_AUTHENTICITY_MISSING");
        blockers.add("NO_VERIFIED_DUPLICATE_DEBIT_ADJUDICATION");
        blockers.add("REFUND_COGS_REVERSAL_REQUIRES_RECEIPT_AND_G1_POLICY");
        blockers.add("DEC_08_FX_POLICY_NOT_APPROVED");
        blockers.add("BANK_LEDGER_AND_V162_V64_NET_PROFIT_UNVERIFIED");
        if(counts.identity()>0)blockers.add("V165_CROSS_SOURCE_IDENTITY_OVERLAP_HOLD");
        if(counts.shared()>0)blockers.add("V168_SHARED_SETTLEMENT_LINE_HOLD");
        if(counts.any()==0)blockers.add("ABSENCE_OF_LOCAL_MATCH_DOES_NOT_PROVE_NO_DUPLICATES");
        return new Report(marketplace.toString(),start.toString(),end.toString(),
            "V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE",
            counts.sources(),counts.identity(),counts.shared(),counts.any(),
            counts.noMatch(),List.copyOf(examples),counts.any()>20,
            List.copyOf(blockers),false,false,false,false,false,false,null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report report(UUID tenant,UUID marketplace,LocalDate start,LocalDate end) {
        CostOwnershipBridgeServiceV186.validate(start,end);
        var scoped=jdbc.query("""
            SELECT m.id FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id
              AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
            """,(r,i)->r.getObject(1,UUID.class),tenant,marketplace);
        if(scoped.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Object[] args={tenant,marketplace,start,end};
        var totals=jdbc.query(AGGREGATE_SQL,(r,i)->new Counts(
            r.getLong("sources"),r.getLong("identity_count"),
            r.getLong("shared_count"),r.getLong("any_count"),
            r.getLong("none_count")),args);
        if(totals.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Full-period collision counters unavailable");
        var sample=jdbc.query(EXAMPLE_SQL,CostCrossSourceRiskServiceV187::flags,args);
        return summarize(marketplace,start,end,totals.getFirst(),sample);
    }
}
