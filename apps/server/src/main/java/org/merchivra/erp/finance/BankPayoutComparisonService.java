package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only bank observation vs Amazon amount diagnostics. Never certifies funds. */
@Service
public class BankPayoutComparisonService {
    private final JdbcTemplate jdbc;
    private final SettlementSourceReviewService reviews;
    public BankPayoutComparisonService(JdbcTemplate jdbc,SettlementSourceReviewService reviews) {
        this.jdbc=jdbc;this.reviews=reviews;
    }
    public record Candidate(String entryRef,String valueDate,String currency,
        String credit,String withheldFee,String declaredFxRate) {}
    public record Result(String settlementRef,String settlementCurrency,
        String declaredNet,String bankCurrency,String observedCredit,
        String withheldFee,String declaredFxRate,String expectedGross,
        String sourceDifference,String state,int candidateCount,
        boolean sourceReviewApproved,boolean bankVerified,boolean ledgerPosted,
        List<Candidate> candidates) {}
    private record Row(String ref,String currency,BigDecimal net,int duplicateReports,
        String entryRef,String date,String bankCurrency,BigDecimal credit,
        BigDecimal fee,BigDecimal fx) {}

    static final String LOOKUP="""
        SELECT r.settlement_reference,r.statement_currency,r.declared_net,
          (SELECT count(*) FROM spapi_settlement_report other
             WHERE other.tenant_id=r.tenant_id
               AND other.seller_marketplace_id=r.seller_marketplace_id
               AND other.settlement_reference=r.settlement_reference) AS report_count,
          b.entry_ref,b.value_date,b.currency_code,b.credit_amount,
          b.withheld_fee,b.declared_fx_rate
        FROM spapi_settlement_report r
        LEFT JOIN bank_payout_observation b ON b.tenant_id=r.tenant_id
           AND b.seller_marketplace_id=r.seller_marketplace_id
           AND b.settlement_ref=r.settlement_reference
        WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?
        ORDER BY b.value_date DESC NULLS LAST,b.id
        LIMIT 3
        """;
    private static String money(BigDecimal value) {
        return value==null?null:value.toPlainString();
    }
    static String decision(int reportCount,int bankCount,boolean sourceApproved,
        BigDecimal net,String reportCurrency,String bankCurrency,
        BigDecimal credit,BigDecimal fee,BigDecimal fx) {
        if(reportCount!=1)return "DUPLICATE_REPORT_SETTLEMENT_ID";
        if(bankCount==0)return "NO_MANUAL_BANK_ENTRY";
        if(bankCount>1)return "AMBIGUOUS_BANK_ENTRY";
        if(!sourceApproved)return "SOURCE_REVIEW_REQUIRED";
        if(net==null)return "REPORT_NET_MISSING";
        if(credit==null||fee==null)return "BANK_AMOUNT_INCOMPLETE";
        if(reportCurrency.equals(bankCurrency)) {
            if(fx!=null && fx.compareTo(BigDecimal.ONE)!=0)
                return "SAME_CURRENCY_RATE_CONFLICT";
            if(credit.add(fee).compareTo(net)!=0)
                return "SAME_CURRENCY_AMOUNT_DIFFERENCE";
            return "MANUAL_AMOUNT_MATCH_UNVERIFIED";
        }
        if(fx==null)return "FX_RATE_REQUIRED";
        if(credit.add(fee).compareTo(net.multiply(fx))!=0)
            return "MANUAL_FX_AMOUNT_DIFFERENCE";
        return "MANUAL_FX_MATCH_UNVERIFIED";
    }

    public Result compare(UUID tenant,UUID mp,UUID reportId) {
        List<Row> rows=jdbc.query(LOOKUP,(r,n)->new Row(
            r.getString("settlement_reference"),r.getString("statement_currency").trim(),
            r.getBigDecimal("declared_net"),r.getInt("report_count"),
            r.getString("entry_ref"),r.getDate("value_date")==null?null:
                r.getDate("value_date").toLocalDate().toString(),
            r.getString("currency_code")==null?null:r.getString("currency_code").trim(),
            r.getBigDecimal("credit_amount"),r.getBigDecimal("withheld_fee"),
            r.getBigDecimal("declared_fx_rate")),tenant,mp,reportId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Amazon report not found in current Marketplace");
        Row report=rows.getFirst();
        List<Candidate> found=new ArrayList<>();
        for(Row row:rows)if(row.entryRef()!=null)
            found.add(new Candidate(row.entryRef(),row.date(),row.bankCurrency(),
                money(row.credit()),money(row.fee()),money(row.fx())));
        var checked=reviews.status(tenant,mp,reportId);
        boolean approved="APPROVED".equals(checked.state())&&checked.evidenceCurrent();
        Row sole=found.size()==1?rows.getFirst():null;
        String code=decision(report.duplicateReports(),found.size(),approved,
            report.net(),report.currency(),
            sole==null?null:sole.bankCurrency(),
            sole==null?null:sole.credit(),sole==null?null:sole.fee(),
            sole==null?null:sole.fx());
        BigDecimal expected=sole==null||report.net()==null?null:
            report.net().multiply(report.currency().equals(sole.bankCurrency())
                ?BigDecimal.ONE:sole.fx()==null?BigDecimal.ONE:sole.fx());
        // Unknown FX must never be translated using an implicit 1.0 exchange.
        if(sole!=null && !report.currency().equals(sole.bankCurrency())
            && sole.fx()==null)expected=null;
        BigDecimal diff=sole==null||expected==null?null:
            sole.credit().add(sole.fee()).subtract(expected);
        return new Result(report.ref(),report.currency(),money(report.net()),
            sole==null?null:sole.bankCurrency(),
            sole==null?null:money(sole.credit()),
            sole==null?null:money(sole.fee()),
            sole==null?null:money(sole.fx()),money(expected),money(diff),
            code,found.size(),approved,false,false,List.copyOf(found));
    }
}
