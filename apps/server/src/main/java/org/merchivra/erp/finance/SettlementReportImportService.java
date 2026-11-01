package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Worker-only LIVE settlement document importer. The HTTP API is read-only.
 * No report bytes, upload URLs, authorization tokens, PII, bank receipts, or
 * "Amazon confirmed" flag are stored. Staging NEVER creates ledger entries.
 */
@Service
public class SettlementReportImportService {
    public static final String TYPE="GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2";
    private final JdbcTemplate jdbc;
    public SettlementReportImportService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Imported(UUID id,UUID marketplaceId,String reportId,String documentId,
        String documentSha256,String settlementReference,String statementCurrency,
        String declaredNet,int lineCount,String importedAt) {}
    public record Line(int lineNumber,String transactionType,String amountType,
        String amountDescription,String orderId,String signedAmount,String currency) {}
    public record Review(long rowCount,String signedLineTotal,String currency,
        long distinctOrderReferences,boolean bankVerified,boolean reconciledToFinances) {}

    /**
     * Only trusted, already approved internal workers can call this method.
     * SQL rechecks the Seller, current Marketplace, grant and Finance role to
     * prevent ingest from a revoked/stale scope. No public import endpoint.
     */
    @Transactional
    public Imported importDocument(SpApiSellerScope approved,String amazonReportId,
        String reportDocumentId,byte[] body,boolean gzip) {
        if(approved==null || !approved.approvedRoles().contains("Finance and Accounting"))
            throw new SecurityException("Verified Finance Seller scope required");
        if(!safeId(amazonReportId,160) || !safeId(reportDocumentId,256))
            throw new IllegalArgumentException("Invalid Amazon Reports identifiers");
        var parsed=SettlementReportParser.parse(body,gzip);
        List<Market> allowed=jdbc.query("""
            SELECT m.id,m.currency_code
            FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id
              AND a.id=m.seller_account_id
            JOIN spapi_seller_authorization g ON g.tenant_id=a.tenant_id
              AND g.seller_account_id=a.id
            JOIN spapi_finances_role_evidence role ON role.tenant_id=g.tenant_id
              AND role.seller_account_id=g.seller_account_id
            WHERE m.tenant_id=? AND a.id=? AND m.marketplace_id=?
              AND a.seller_id=? AND a.region=? AND a.connection_status='CONNECTED'
              AND g.status='VERIFIED' AND g.seller_id=a.seller_id
              AND g.region=a.region AND role.role_code='Finance and Accounting'
              AND role.evidence_kind='EXTERNAL_APP_ROLE_REVIEW'
              AND role.grant_verified_at=g.verified_at
              AND role.expires_at>now() AND role.revoked_at IS NULL
              AND EXISTS (
                SELECT 1 FROM spapi_marketplace_verification v
                WHERE v.tenant_id=g.tenant_id AND v.seller_account_id=g.seller_account_id
                  AND v.marketplace_id=m.marketplace_id
                  AND v.verified_at>now()-interval '24 hours')
              AND EXISTS (
                SELECT 1 FROM membership member
                JOIN app_user u ON u.id=member.user_id AND u.status='ACTIVE'
                JOIN role_binding reviewer ON reviewer.tenant_id=member.tenant_id
                  AND reviewer.user_id=member.user_id
                  AND reviewer.role_code='SECURITY_REVIEWER'
                JOIN role_binding owner ON owner.tenant_id=member.tenant_id
                  AND owner.user_id=member.user_id AND owner.role_code='OWNER'
                  AND owner.seller_account_id IS NULL
                WHERE member.tenant_id=role.tenant_id
                  AND member.user_id=role.reviewed_by
                  AND member.status='ACTIVE'
                  AND (reviewer.seller_account_id IS NULL
                    OR reviewer.seller_account_id=a.id))
            FOR SHARE OF m,a,g,role
            """,(rs,n)->new Market(rs.getObject(1,UUID.class),
                rs.getString(2).trim()),
            approved.tenantId(),approved.sellerAccountId(),approved.marketplaceId(),
            approved.sellerId(),approved.region().name());
        if(allowed.size()!=1)
            throw new SecurityException("Current Seller Finance scope no longer approved");
        Market market=allowed.getFirst();
        if(!market.currency().equals(parsed.currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Report currency disagrees with Marketplace; manual FX review required");
        String hash=sha(body);
        UUID id=UUID.randomUUID();
        try {
            int inserted=jdbc.update("""
                INSERT INTO spapi_settlement_report
                  (id,tenant_id,seller_marketplace_id,amazon_report_id,
                   report_document_id,report_type,state,source_origin,
                   document_sha256,settlement_reference,statement_currency,
                   declared_net,line_count)
                VALUES (?,?,?,?,?,?,'IMPORTED','LIVE',?,?,?,?,?)
                ON CONFLICT (tenant_id,seller_marketplace_id,amazon_report_id)
                  DO NOTHING
                """,id,approved.tenantId(),market.id(),amazonReportId,
                reportDocumentId,TYPE,hash,parsed.settlementId(),
                parsed.currency(),parsed.declaredNet(),parsed.lines().size());
            if(inserted==1) {
                for(var line:parsed.lines()) {
                    jdbc.update("""
                        INSERT INTO spapi_settlement_report_line
                          (tenant_id,report_id,line_number,transaction_type,amount_type,
                           amount_description,external_order_id,signed_amount,currency_code)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """,approved.tenantId(),id,line.number(),line.transactionType(),
                        line.amountType(),line.amountDescription(),line.orderId(),
                        line.amount(),parsed.currency());
                }
            }
        } catch(DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Report document belongs to another immutable import");
        }
        List<Imported> found=jdbc.query("""
            SELECT id,seller_marketplace_id,amazon_report_id,report_document_id,
                   document_sha256,settlement_reference,statement_currency,
                   declared_net,line_count,imported_at
            FROM spapi_settlement_report
            WHERE tenant_id=? AND seller_marketplace_id=? AND amazon_report_id=?
            """,(rs,n)->new Imported(rs.getObject("id",UUID.class),
                rs.getObject("seller_marketplace_id",UUID.class),
                rs.getString("amazon_report_id"),rs.getString("report_document_id"),
                rs.getString("document_sha256"),rs.getString("settlement_reference"),
                rs.getString("statement_currency").trim(),
                rs.getBigDecimal("declared_net")==null?null:
                    rs.getBigDecimal("declared_net").toPlainString(),
                rs.getInt("line_count"),
                rs.getObject("imported_at",java.time.OffsetDateTime.class)
                    .toInstant().toString()),
            approved.tenantId(),market.id(),amazonReportId);
        if(found.size()!=1)throw new IllegalStateException("Report staging unavailable");
        Imported saved=found.getFirst();
        if(!saved.documentId().equals(reportDocumentId)
            || !saved.documentSha256().equals(hash)
            || !saved.settlementReference().equals(parsed.settlementId())
            || !saved.statementCurrency().equals(parsed.currency())
            || saved.lineCount()!=parsed.lines().size()
            || !equalMoney(saved.declaredNet(),parsed.declaredNet())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Report ID was reused with different source contents");
        }
        return saved;
    }

    public List<Imported> list(UUID tenant,UUID marketplace) {
        return jdbc.query("""
            SELECT id,seller_marketplace_id,amazon_report_id,report_document_id,
                   document_sha256,settlement_reference,statement_currency,
                   declared_net,line_count,imported_at
            FROM spapi_settlement_report
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY imported_at DESC,id DESC LIMIT 50
            """,(rs,n)->new Imported(rs.getObject("id",UUID.class),
                rs.getObject("seller_marketplace_id",UUID.class),
                rs.getString("amazon_report_id"),rs.getString("report_document_id"),
                rs.getString("document_sha256"),rs.getString("settlement_reference"),
                rs.getString("statement_currency").trim(),
                rs.getBigDecimal("declared_net")==null?null:
                    rs.getBigDecimal("declared_net").toPlainString(),
                rs.getInt("line_count"),
                rs.getObject("imported_at",java.time.OffsetDateTime.class)
                    .toInstant().toString()),tenant,marketplace);
    }

    public List<Line> lines(UUID tenant,UUID marketplace,UUID reportId) {
        return jdbc.query("""
            SELECT l.line_number,l.transaction_type,l.amount_type,
                l.amount_description,l.external_order_id,l.signed_amount,l.currency_code
            FROM spapi_settlement_report_line l
            JOIN spapi_settlement_report r ON r.tenant_id=l.tenant_id AND r.id=l.report_id
            WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?
            ORDER BY l.line_number LIMIT 100
            """,(rs,n)->new Line(rs.getInt(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getBigDecimal(6).toPlainString(),
                rs.getString(7).trim()),tenant,marketplace,reportId);
    }

    public Review review(UUID tenant,UUID marketplace,UUID reportId) {
        return jdbc.query("""
            SELECT COUNT(*) row_count,COALESCE(SUM(l.signed_amount),0) signed_total,
                   r.statement_currency,
                   COUNT(DISTINCT l.external_order_id) referenced_orders
            FROM spapi_settlement_report r
            LEFT JOIN spapi_settlement_report_line l
              ON l.tenant_id=r.tenant_id AND l.report_id=r.id
            WHERE r.tenant_id=? AND r.seller_marketplace_id=? AND r.id=?
            GROUP BY r.statement_currency
            """,(rs,n)->new Review(rs.getLong("row_count"),
                rs.getBigDecimal("signed_total").toPlainString(),
                rs.getString("statement_currency").trim(),
                rs.getLong("referenced_orders"),false,false),
            tenant,marketplace,reportId).stream().findFirst().orElseThrow(()->
                new ResponseStatusException(HttpStatus.NOT_FOUND,"Report not found"));
    }

    private record Market(UUID id,String currency) {}
    private static boolean safeId(String value,int max) {
        return value!=null && value.length()>=8 && value.length()<=max
            && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    }
    private static boolean equalMoney(String existing,BigDecimal requested) {
        return existing==null?requested==null:
            requested!=null && new BigDecimal(existing).compareTo(requested)==0;
    }
    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
