package org.merchivra.erp.finance;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Idempotent, immutable manual-only bank observation import.
 * It does NOT authenticate a bank statement, establish bank balance, or
 * create Finances events/ledger entries. No raw CSV persists.
 */
@Service
public class BankPayoutImportService {
    private final JdbcTemplate jdbc;
    public BankPayoutImportService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Import(UUID id,UUID marketplaceId,String statementRef,
        String fileSha256,int lineCount,String sourceOrigin,
        String importedBy,String importedAt) {}
    public record Payout(UUID id,String entryRef,String settlementRef,
        String valueDate,String bankCurrency,String creditAmount,
        String withheldFee,String declaredFxRate,String sourceOrigin) {}

    static String sha(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
    private static Import batch(java.sql.ResultSet r,int n)
        throws java.sql.SQLException {
        return new Import(r.getObject("id",UUID.class),
            r.getObject("seller_marketplace_id",UUID.class),
            r.getString("statement_ref"),r.getString("file_sha256"),
            r.getInt("line_count"),r.getString("source_origin"),
            r.getObject("imported_by",UUID.class).toString(),
            r.getObject("imported_at",java.time.OffsetDateTime.class).toInstant().toString());
    }

    private Import existing(UUID tenant,UUID mp,String statementRef) {
        return jdbc.query("""
            SELECT id,seller_marketplace_id,statement_ref,file_sha256,
              line_count,source_origin,imported_by,imported_at
            FROM bank_payout_import_batch
            WHERE tenant_id=? AND seller_marketplace_id=? AND statement_ref=?
            """,BankPayoutImportService::batch,tenant,mp,statementRef)
            .stream().findFirst().orElse(null);
    }

    @Transactional
    public Import importCsv(UUID tenant,UUID mp,UUID actor,
        String statementRef,byte[] body) {
        if(tenant==null||mp==null||actor==null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        BankPayoutCsv.reference(statementRef);
        List<BankPayoutCsv.Entry> entries=BankPayoutCsv.parse(body);
        Integer exists=jdbc.queryForObject("""
            SELECT count(*) FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,Integer.class,tenant,mp);
        if(exists==null||exists!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Marketplace unavailable");
        String digest=sha(body);
        UUID id=UUID.randomUUID();
        final int inserted;
        try {
            inserted=jdbc.update("""
                INSERT INTO bank_payout_import_batch
                  (id,tenant_id,seller_marketplace_id,statement_ref,file_sha256,
                    line_count,imported_by)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,seller_marketplace_id,statement_ref)
                    DO NOTHING
                """,id,tenant,mp,statementRef,digest,entries.size(),actor);
        } catch(DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Same bank CSV already assigned to another statement reference");
        }
        if(inserted==1) {
            for(var entry:entries) {
                try {
                    jdbc.update("""
                        INSERT INTO bank_payout_observation
                          (id,tenant_id,seller_marketplace_id,batch_id,
                           row_number,entry_ref,settlement_ref,value_date,
                           currency_code,credit_amount,withheld_fee,declared_fx_rate)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """,UUID.randomUUID(),tenant,mp,id,entry.line(),
                        entry.entryRef(),entry.settlementRef(),entry.valueDate(),
                        entry.currency(),entry.credit(),entry.fee(),entry.fxRate());
                } catch(DuplicateKeyException ex) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bank entry ID already imported under this Marketplace");
                }
            }
        }
        Import saved=existing(tenant,mp,statementRef);
        if(saved==null||!saved.fileSha256().equals(digest)
            ||saved.lineCount()!=entries.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Statement reference already exists with different immutable contents");
        return saved;
    }

    public List<Import> list(UUID tenant,UUID mp) {
        return jdbc.query("""
            SELECT id,seller_marketplace_id,statement_ref,file_sha256,
              line_count,source_origin,imported_by,imported_at
            FROM bank_payout_import_batch
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY imported_at DESC,id DESC LIMIT 50
            """,BankPayoutImportService::batch,tenant,mp);
    }
    public List<Payout> lines(UUID tenant,UUID mp,UUID batch) {
        return jdbc.query("""
            SELECT b.id,b.entry_ref,b.settlement_ref,b.value_date,
              b.currency_code,b.credit_amount,b.withheld_fee,
              b.declared_fx_rate,b.evidence_origin
            FROM bank_payout_observation b
            JOIN bank_payout_import_batch imp ON imp.tenant_id=b.tenant_id
              AND imp.id=b.batch_id AND imp.seller_marketplace_id=b.seller_marketplace_id
            WHERE b.tenant_id=? AND b.seller_marketplace_id=? AND b.batch_id=?
            ORDER BY b.row_number LIMIT 50
            """,(r,n)->new Payout(r.getObject("id",UUID.class),r.getString("entry_ref"),
                r.getString("settlement_ref"),r.getDate("value_date").toLocalDate().toString(),
                r.getString("currency_code").trim(),
                r.getBigDecimal("credit_amount").toPlainString(),
                r.getBigDecimal("withheld_fee").toPlainString(),
                r.getBigDecimal("declared_fx_rate")==null?null:
                    r.getBigDecimal("declared_fx_rate").toPlainString(),
                r.getString("evidence_origin")),tenant,mp,batch);
    }
}
