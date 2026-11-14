package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V167: human-proposed links between manual costs and actual imported settlement
 * report LINES. Even matching order reference AND debit amount is only similar
 * local evidence, not a proven duplicate charge, accounting post or hold release.
 */
@Service
public class CostSettlementLineEvidenceService {
    private final JdbcTemplate jdbc;
    public CostSettlementLineEvidenceService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Link(String id,String sourceId,String reportId,int lineNumber,
        String sourceAmount,String sourceCurrency,String signedLineAmount,
        String lineCurrency,String similarity,String state,String evidenceOrigin,
        String recordedBy,String recordedAt,String reviewedBy,String reviewedAt,
        boolean confirmedDuplicate,boolean financialHoldCleared,boolean netProfitVerified) {}
    public record Audit(String action,String actorId,String occurredAt) {}
    private record Source(UUID maker,String key,BigDecimal amount,String currency) {}
    private record Line(String externalOrderId,BigDecimal signedAmount,String currency) {}
    private record Record(UUID id,UUID source,UUID report,int lineNumber,
        BigDecimal sourceAmount,String sourceCurrency,BigDecimal lineAmount,
        String lineCurrency,String similarity,String state,String evidenceOrigin,
        UUID recorder,OffsetDateTime recordedAt,UUID reviewer,OffsetDateTime reviewedAt) {}

    static String similarity(String sourceKey,BigDecimal cost,String costCurrency,
        String orderId,BigDecimal signedLine,String lineCurrency) {
        if(sourceKey==null || cost==null || cost.signum()<=0
            || costCurrency==null || signedLine==null || lineCurrency==null)
            throw new IllegalArgumentException("Incomplete cost or line evidence");
        if(!costCurrency.equals(lineCurrency))return "CURRENCY_MISMATCH";
        if(signedLine.signum()>=0)return "CREDIT_OR_ZERO_NOT_DEBIT";
        boolean ref=orderId!=null && !orderId.isBlank() && sourceKey.equals(orderId);
        boolean amount=cost.compareTo(signedLine.negate())==0;
        if(ref && amount)return "SIMILAR_REFERENCE_AND_AMOUNT";
        if(ref)return "SIMILAR_REFERENCE_ONLY";
        if(amount)return "AMOUNT_ONLY_NO_IDENTITY";
        return "UNRELATED_LINE_NO_EVIDENCE";
    }

    static void independent(UUID maker,UUID recorder,UUID reviewer,
        String disposition,String reason) {
        if(reviewer==null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(reviewer.equals(maker)||reviewer.equals(recorder))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Independent reviewer cannot be source maker or link recorder");
        if(!List.of("ESCALATED_NEEDS_PROOF","POTENTIAL_DUPLICATE_UNVERIFIED",
            "NOT_SAME_CHARGE_UNVERIFIED").contains(disposition)
            ||reason==null||reason.strip().length()<10||reason.length()>500
            ||reason.indexOf('\n')>=0||reason.indexOf('\r')>=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid unverified cost evidence disposition or reason");
    }

    private Source source(UUID tenant,UUID marketplace,UUID sourceId,boolean lock) {
        var result=jdbc.query("""
            SELECT s.created_by,s.source_key,s.amount,s.currency_code
            FROM finance_cost_source_v91 s
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            """+(lock?" FOR UPDATE OF s":""),
            (r,i)->new Source(r.getObject(1,UUID.class),r.getString(2),
                r.getBigDecimal(3),r.getString(4).trim()),
            tenant,marketplace,sourceId);
        if(result.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return result.getFirst();
    }
    private Line line(UUID tenant,UUID marketplace,UUID reportId,int lineNo) {
        var result=jdbc.query("""
            SELECT l.external_order_id,l.signed_amount,l.currency_code
            FROM spapi_settlement_report r
            JOIN spapi_settlement_report_line l
              ON l.tenant_id=r.tenant_id AND l.report_id=r.id
            WHERE r.tenant_id=? AND r.seller_marketplace_id=?
              AND r.id=? AND l.line_number=?
              AND r.state='IMPORTED' AND r.source_origin='LIVE'
            FOR SHARE OF r,l
            """,(r,i)->new Line(r.getString(1),r.getBigDecimal(2),
                r.getString(3).trim()),tenant,marketplace,reportId,lineNo);
        if(result.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Imported settlement line unavailable");
        return result.getFirst();
    }
    private static Record record(ResultSet r,int i)throws SQLException {
        return new Record(r.getObject("id",UUID.class),
            r.getObject("source_id",UUID.class),
            r.getObject("report_id",UUID.class),r.getInt("line_number"),
            r.getBigDecimal("source_amount"),r.getString("source_currency").trim(),
            r.getBigDecimal("line_signed_amount"),r.getString("line_currency").trim(),
            r.getString("similarity"),r.getString("state"),
            r.getString("evidence_origin"),r.getObject("recorded_by",UUID.class),
            r.getObject("recorded_at",OffsetDateTime.class),
            r.getObject("reviewed_by",UUID.class),
            r.getObject("reviewed_at",OffsetDateTime.class));
    }
    private static Link view(Record r) {
        return new Link(r.id().toString(),r.source().toString(),
            r.report().toString(),r.lineNumber(),r.sourceAmount().toPlainString(),
            r.sourceCurrency(),r.lineAmount().toPlainString(),r.lineCurrency(),
            r.similarity(),r.state(),r.evidenceOrigin(),r.recorder().toString(),
            r.recordedAt().toInstant().toString(),
            r.reviewer()==null?null:r.reviewer().toString(),
            r.reviewedAt()==null?null:r.reviewedAt().toInstant().toString(),
            false,false,false);
    }
    private Record find(UUID tenant,UUID marketplace,UUID sourceId,UUID linkId,
        boolean lock) {
        var records=jdbc.query("""
            SELECT * FROM finance_cost_settlement_line_link_v94
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_id=? AND id=?
            """+(lock?" FOR UPDATE":""),
            CostSettlementLineEvidenceService::record,
            tenant,marketplace,sourceId,linkId);
        if(records.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return records.getFirst();
    }
    private void audit(UUID tenant,UUID link,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO finance_cost_settlement_line_audit_v94
                (id,tenant_id,link_id,action,actor_id)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,link,action,actor);
    }

    @Transactional
    public Link attach(UUID tenant,UUID marketplace,UUID sourceId,
        UUID reportId,Integer lineNumber,UUID actor) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(reportId==null||lineNumber==null||lineNumber<1||lineNumber>10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An actual settlement report ID and line number are required");
        // Lock the immutable manual source to serialize same-source replays.
        Source s=source(tenant,marketplace,sourceId,true);
        Line l=line(tenant,marketplace,reportId,lineNumber);
        String category=similarity(s.key(),s.amount(),s.currency(),
            l.externalOrderId(),l.signedAmount(),l.currency());
        var existing=jdbc.query("""
            SELECT * FROM finance_cost_settlement_line_link_v94
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_id=?
              AND report_id=? AND line_number=?
            """,CostSettlementLineEvidenceService::record,
            tenant,marketplace,sourceId,reportId,lineNumber);
        if(!existing.isEmpty()) {
            var prior=existing.getFirst();
            if(!prior.similarity().equals(category)
                ||prior.sourceAmount().compareTo(s.amount())!=0
                ||!prior.sourceCurrency().equals(s.currency())
                ||prior.lineAmount().compareTo(l.signedAmount())!=0
                ||!prior.lineCurrency().equals(l.currency()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Previously linked line evidence changed");
            return view(prior); // Idempotent: no second OPEN audit.
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_cost_settlement_line_link_v94
               (id,tenant_id,seller_marketplace_id,source_id,report_id,
                line_number,source_amount,source_currency,line_signed_amount,
                line_currency,similarity,recorded_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenant,marketplace,sourceId,reportId,lineNumber,
            s.amount(),s.currency(),l.signedAmount(),l.currency(),category,actor);
        audit(tenant,id,actor,"OPEN");
        return view(find(tenant,marketplace,sourceId,id,false));
    }
    public List<Link> list(UUID tenant,UUID marketplace,UUID sourceId) {
        source(tenant,marketplace,sourceId,false);
        return jdbc.query("""
            SELECT * FROM finance_cost_settlement_line_link_v94
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_id=?
            ORDER BY recorded_at DESC,id DESC LIMIT 100
            """,CostSettlementLineEvidenceService::record,
            tenant,marketplace,sourceId).stream()
            .map(CostSettlementLineEvidenceService::view).toList();
    }
    @Transactional
    public Link decide(UUID tenant,UUID marketplace,UUID sourceId,
        UUID linkId,UUID reviewer,String disposition,String reason) {
        if(reviewer==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(!List.of("ESCALATED_NEEDS_PROOF","POTENTIAL_DUPLICATE_UNVERIFIED",
            "NOT_SAME_CHARGE_UNVERIFIED").contains(disposition)
            ||reason==null||reason.strip().length()<10||reason.length()>500
            ||reason.indexOf('\n')>=0||reason.indexOf('\r')>=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid unverified evidence review");
        Source s=source(tenant,marketplace,sourceId,true);
        Record current=find(tenant,marketplace,sourceId,linkId,true);
        independent(s.maker(),current.recorder(),reviewer,disposition,reason);
        if(!"OPEN".equals(current.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cost evidence link has an irreversible disposition");
        jdbc.update("""
            UPDATE finance_cost_settlement_line_link_v94
            SET state=?,reviewed_by=?,reviewed_at=now(),review_reason=?
            WHERE tenant_id=? AND seller_marketplace_id=? AND source_id=?
              AND id=? AND state='OPEN'
            """,disposition,reviewer,reason.strip(),
            tenant,marketplace,sourceId,linkId);
        audit(tenant,linkId,reviewer,disposition);
        return view(find(tenant,marketplace,sourceId,linkId,false));
    }
    public List<Audit> history(UUID tenant,UUID marketplace,UUID sourceId,UUID linkId) {
        source(tenant,marketplace,sourceId,false);
        find(tenant,marketplace,sourceId,linkId,false);
        return jdbc.query("""
            SELECT action,actor_id,occurred_at
            FROM finance_cost_settlement_line_audit_v94
            WHERE tenant_id=? AND link_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,i)->new Audit(r.getString(1),
                r.getObject(2,UUID.class).toString(),
                r.getObject(3,OffsetDateTime.class).toInstant().toString()),
            tenant,linkId);
    }
}
