package org.merchivra.erp.finance;

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
 * V230: live read-only, single RR snapshot dossier from existing V91/V229 and
 * V165/V94/V102/V182. Describes which THIRD-PARTY evidence must be supplied.
 * This never authenticates a user-claimed digest, issuer, bank debit or charge,
 * and never promotes V91 metadata into V162/V64/COGS/profit.
 */
@Service
public class CostExternalProofDossierServiceV230 {
    private final JdbcTemplate jdbc;
    private final CostSourceAuditIntegrityServiceV229 sourceAudit;
    private final CostAttributionEvidenceService attribution;

    public CostExternalProofDossierServiceV230(JdbcTemplate jdbc,
        CostSourceAuditIntegrityServiceV229 sourceAudit,
        CostAttributionEvidenceService attribution) {
        this.jdbc=jdbc;
        this.sourceAudit=sourceAudit;
        this.attribution=attribution;
    }

    public record Requirement(String code,String description,
        boolean independentlyVerified) {}
    public record Dossier(String sourceId,String marketplaceId,
        String category,String originalCurrency,String sourceReviewState,
        String localAuditStatus,String localAttributionStatus,
        boolean localCollisionHeld,boolean localSourceAuditComplete,
        boolean independentMetadataReviewComplete,
        String localDossierStatus,List<String> blockerCodes,
        List<Requirement> requiredExternalProof,
        boolean originalBytesVerified,boolean issuerIdentityVerified,
        boolean originalChargeVerified,boolean duplicateDebitExcluded,
        boolean settlementOrPaymentReconciled,
        boolean actualLandedOrRefundCogsVerified,
        boolean accountingPolicyApproved,boolean eligibleForRealizedProfit,
        String realizedNetProfit) {}
    record Scope(String category,String currency) {}

    private static Requirement requirement(String code,String description) {
        return new Requirement(code,description,false);
    }
    /** Category-specific issuer/line/payment requirements do not imply that
     * non-platform costs must appear in an Amazon settlement report. */
    static List<Requirement> requirements(String category) {
        var required=new ArrayList<Requirement>();
        required.add(requirement("ORIGINAL_DOCUMENT_BYTES_AND_SHA256",
            "独立取得的原始文件字节与 SHA-256 重算；手工声明摘要不是验证"));
        required.add(requirement("INDEPENDENT_ISSUER_AUTHORITY",
            "签发方真实主体、文件/接口的合法访问授权及撤销证据"));
        switch(category) {
            case "ADS_SPEND" -> required.add(requirement(
                "AMAZON_ADS_REPORT_AND_BILLING_LINE",
                "真实 Ads 报告/账单的账户、广告活动、原币负担行与期间归属"));
            case "FBA_STORAGE" -> required.add(requirement(
                "AMAZON_FBA_FEE_REPORT_LINE",
                "真实 FBA 仓储费用文档/报告行与店铺原币期间"));
            case "PAYMENT_AND_FX" -> required.add(requirement(
                "BANK_OR_PAYMENT_PROVIDER_ENTRY_AND_FX_POLICY",
                "银行/支付服务商真实扣款或换汇记录、原币及获准汇率口径"));
            case "INBOUND_FREIGHT","FBM_SHIPPING" -> required.add(requirement(
                "CARRIER_INVOICE_SHIPMENT_AND_RECEIPT",
                "承运商/货代独立运单、发票、已签收批次与实际承担者"));
            case "RETURN_HANDLING" -> required.add(requirement(
                "RETURN_DISPOSITION_RECEIPT_AND_COGS_REVERSAL",
                "独立退货处置、实收/QC、原出库批次及 COGS 冲回证据"));
            case "IMPORT_DUTY" -> required.add(requirement(
                "CUSTOMS_ENTRY_DUTY_AND_SHIPMENT",
                "真实报关/税单与对应货件及适用税务口径"));
            case "OTHER_OPERATING" -> required.add(requirement(
                "THIRD_PARTY_SERVICE_CONTRACT_AND_INVOICE",
                "实际服务签发方、合同、发票与履约/支付证明"));
            default -> throw new IllegalArgumentException("Unknown cost category");
        }
        required.add(requirement("EXACT_CHARGE_ORIGINAL_CURRENCY_ALLOCATION",
            "原币费用/负数退款、订单-SKU-批次/期间分配与全局守恒"));
        required.add(requirement("INDEPENDENT_PAYMENT_AND_DUPLICATE_REVIEW",
            "独立资金流/结算复核、V165/V94/V95 跨来源冲突及一笔费用唯一归属"));
        required.add(requirement("ACCOUNTING_POLICY_AND_G1_DEC_APPROVAL",
            "会计期间、退款 COGS、DEC-06/07/08 与生产 G1 正式批准"));
        return List.copyOf(required);
    }

    static Dossier compose(UUID sourceId,UUID mp,Scope source,
        CostSourceAuditIntegrityServiceV229.Evidence audit,
        CostAttributionEvidenceService.Assessment local) {
        if(sourceId==null||mp==null||source==null||audit==null||local==null
            ||!sourceId.toString().equals(audit.sourceId())
            ||!sourceId.toString().equals(local.sourceId())
            ||!mp.toString().equals(audit.marketplaceId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source evidence snapshot inconsistent");
        var proof=requirements(source.category());
        boolean review="REVIEWED_UNVERIFIED".equals(audit.reviewState());
        boolean collision=local.localCollisionApprovalHeld()
            ||"CONFLICT_EVIDENCE".equals(local.status());
        boolean auditComplete=audit.localAuditComplete();
        String status;
        if(!auditComplete) status="LOCAL_AUDIT_INCONSISTENT_HOLD";
        else if("REJECTED".equals(audit.reviewState())) status="LOCAL_SOURCE_REJECTED";
        else if(!review) status="LOCAL_METADATA_REVIEW_PENDING";
        else if(collision) status="LOCAL_CROSS_SOURCE_CONFLICT_HOLD";
        else status="LOCAL_REVIEW_COMPLETE_EXTERNAL_UNVERIFIED";
        var reasons=new ArrayList<String>();
        reasons.addAll(audit.blockerCodes());
        if(collision) reasons.add("V165_V94_LOCAL_CROSS_SOURCE_COLLISION_HOLD");
        if("UNPROVEN".equals(local.status()))
            reasons.add("V182_LOCAL_SETTLEMENT_ATTRIBUTION_UNPROVEN");
        for(var req:proof)reasons.add(req.code());
        // External provenance has no trusted attestation provider in this
        // version. Never infer it from local SHA, amount, matching link or
        // independent Owner review.
        return new Dossier(sourceId.toString(),mp.toString(),
            source.category(),source.currency(),audit.reviewState(),
            auditComplete?"LOCAL_AUDIT_COMPLETE":"LOCAL_AUDIT_INCONSISTENT",
            local.status(),collision,auditComplete,
            review&&auditComplete,status,List.copyOf(reasons),proof,
            false,false,false,false,false,false,false,false,null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Dossier inspect(UUID tenant,UUID marketplace,UUID sourceId) {
        if(tenant==null||marketplace==null||sourceId==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // All three existing evidence checks join this *same* RR transaction.
        // Audit check first: inactive tenant/foreign source returns 404 before
        // probing other identity namespaces. No raw SHA, invoice or reviewer
        // reason enters the response.
        var audit=sourceAudit.inspect(tenant,marketplace,sourceId);
        var scope=jdbc.query("""
            SELECT s.category,btrim(s.currency_code) AS currency
            FROM finance_cost_source_v91 s
            JOIN seller_marketplace m ON m.tenant_id=s.tenant_id
              AND m.id=s.seller_marketplace_id
            JOIN tenant t ON t.id=s.tenant_id AND t.status='ACTIVE'
            WHERE s.tenant_id=? AND s.seller_marketplace_id=? AND s.id=?
            """,(r,i)->new Scope(r.getString("category"),
                r.getString("currency")),tenant,marketplace,sourceId);
        if(scope.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source scope changed during evidence read");
        var local=attribution.assess(tenant,marketplace,sourceId);
        return compose(sourceId,marketplace,scope.getFirst(),audit,local);
    }
}
