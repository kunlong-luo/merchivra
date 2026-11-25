package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Internal candidate arithmetic, not an externally authenticated landed-cost ledger. */
@Service
public class ReceiptCostCompositionServiceV249 {
    static final String CLAIM_SQL = """
        SELECT c.id,c.marketplace_id,c.assigned_quantity,c.snapshot_accepted_quantity,
          c.snapshot_receipt_quantity,btrim(c.snapshot_currency) AS currency,
          c.snapshot_unit_cost,v.integrity_state,r.receipt_id AS range_receipt,
          r.basis_quantity,r.unit_from,r.unit_to
        FROM finance_sale_lot_claim_v106 c
        LEFT JOIN finance_sale_lot_integrity_v106 v
          ON v.tenant_id=c.tenant_id AND v.claim_id=c.id
        LEFT JOIN finance_sale_lot_cost_range_v117 r
          ON r.tenant_id=c.tenant_id AND r.claim_id=c.id
        WHERE c.tenant_id=? AND c.receipt_id=?
        ORDER BY c.id LIMIT 1001
        """;
    record Claim(UUID id,UUID marketplace,int quantity,int accepted,int received,
        String currency,BigDecimal price,String state,UUID rangeReceipt,
        Integer basis,Integer from,Integer to) {}
    public record Component(String sourceId,String category,
        CostAllocationEngine.ReceiptPartition partition) {}
    public record SaleShare(String claimId,String candidateCost) {}
    public record Candidate(String state,String currency,String receiptCandidateCost,
        String allocatedCandidateCost,String unallocatedCandidateCost,
        List<Component> components,List<SaleShare> saleShares,List<String> blockerCodes,
        boolean actualBatchCogsVerified,String actualBatchCogs,String realizedNetProfit) {}

    private final JdbcTemplate jdbc;
    private final ReceiptCostIntegrityServiceV216 audit;
    private final TransactionTemplate read;
    private final TransactionTemplate captureTx;
    public ReceiptCostCompositionServiceV249(JdbcTemplate jdbc,
        ReceiptCostIntegrityServiceV216 audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.audit=audit;
        read=new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        captureTx=new TransactionTemplate(manager);
        captureTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        captureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // No public route or write method. One independent RR snapshot, not the
    // standalone V216 double-read freshness guarantee; no network inside it.
    public Candidate preview(UUID tenant,UUID marketplace,UUID receipt) {
        if(tenant==null||marketplace==null||receipt==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return read.execute(ignored->readCandidate(tenant,marketplace,receipt));
    }

    // Capture a versioned LOCAL observation for exactly one persisted claim.
    // Not a sale-time posting, no persistence, no external proof. Identity
    // and candidate amounts share the same independent RR snapshot.
    public RefundOriginalCostSliceEngineV255.Basis prepareRefundBasis(
        UUID tenant,UUID marketplace,UUID receipt,UUID claimId) {
        if(tenant==null||marketplace==null||receipt==null||claimId==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return read.execute(ignored->captureBasis(tenant,marketplace,receipt,claimId));
    }

    // Internal only; deliberately no HTTP write endpoint. One observation per
    // claim. A later different cost cannot replace or silently version it.
    RefundOriginalCostSliceEngineV255.Basis persistRefundBasis(
        UUID tenant,UUID marketplace,UUID receipt,UUID claimId,UUID actor) {
        if(tenant==null||marketplace==null||receipt==null||claimId==null||actor==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return captureTx.execute(ignored->{
            var basis=captureBasis(tenant,marketplace,receipt,claimId);
            jdbc.update("""
                INSERT INTO finance_original_cost_basis_v119
                  (tenant_id,claim_id,basis_sha256,canonical_payload,recorded_by)
                VALUES (?,?,?,?,?)
                """,tenant,claimId,basis.sha256(),
                RefundOriginalCostSliceEngineV255.canonicalPayload(basis),actor);
            return basis;
        });
    }

    private RefundOriginalCostSliceEngineV255.Basis captureBasis(
        UUID tenant,UUID marketplace,UUID receipt,UUID claimId) {
            var candidate=readCandidate(tenant,marketplace,receipt);
            var identities=jdbc.query("""
                SELECT sales_order_id,order_item_id FROM finance_sale_lot_claim_v106
                WHERE tenant_id=? AND marketplace_id=? AND receipt_id=? AND id=?
                """,(rs,n)->new RefundOriginalCostSliceEngineV255.Identity(
                    tenant,marketplace,receipt,claimId,
                    rs.getObject("sales_order_id",UUID.class),
                    rs.getObject("order_item_id",UUID.class)),
                tenant,marketplace,receipt,claimId);
            if(identities.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            try {
                return RefundOriginalCostSliceEngineV255.capture(identities.getFirst(),candidate);
            } catch(IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot capture intact original local cost candidate",invalid);
            }
    }

    private Candidate readCandidate(UUID tenant,UUID marketplace,UUID receipt) {
            var source=audit.snapshot(tenant,marketplace,receipt);
            var claims=jdbc.query(CLAIM_SQL,(rs,n)->new Claim(
                rs.getObject("id",UUID.class),rs.getObject("marketplace_id",UUID.class),
                rs.getInt("assigned_quantity"),rs.getInt("snapshot_accepted_quantity"),
                rs.getInt("snapshot_receipt_quantity"),rs.getString("currency"),
                rs.getBigDecimal("snapshot_unit_cost"),rs.getString("integrity_state"),
                rs.getObject("range_receipt",UUID.class),rs.getObject("basis_quantity",Integer.class),
                rs.getObject("unit_from",Integer.class),rs.getObject("unit_to",Integer.class)),
                tenant,receipt);
            Boolean foreignSource=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM finance_receipt_cost_line_v112 l
                JOIN finance_receipt_cost_plan_v112 p
                  ON p.tenant_id=l.tenant_id AND p.id=l.plan_id
                JOIN finance_cost_source_v91 s
                  ON s.tenant_id=p.tenant_id AND s.id=p.source_id
                WHERE l.tenant_id=? AND l.receipt_id=? AND s.seller_marketplace_id<>?)
                """,Boolean.class,tenant,receipt,marketplace);
            try {
                return compose(marketplace,receipt,source,claims,
                    !Boolean.FALSE.equals(foreignSource));
            } catch(IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Receipt candidate amount or identity inconsistent",invalid);
            }
    }

    private static Candidate hold(String reason) {
        return new Candidate("LOCAL_COST_COMPOSITION_HOLD",null,null,null,null,
            List.of(),List.of(),List.of(reason,"EXTERNAL_COST_AND_G1_UNAPPROVED"),
            false,null,null);
    }

    static Candidate compose(UUID marketplace,UUID receipt,
        ReceiptCostIntegrityServiceV216.Snapshot snapshot,List<Claim> claims,
        boolean foreignSource) {
        if(foreignSource||claims.stream().anyMatch(c->!marketplace.equals(c.marketplace())))
            return hold("CROSS_MARKETPLACE_RECEIPT_SCOPE_UNRESOLVED");
        if(claims.isEmpty()||claims.size()>1000)
            return hold("RECEIPT_CLAIM_POPULATION_MISSING_OR_UNBOUNDED");
        var first=claims.getFirst();
        int basis=first.accepted();
        if(basis<=0||first.received()!=basis||first.price()==null
            ||first.currency()==null||!first.currency().matches("[A-Z]{3}"))
            return hold("PURCHASE_OR_REJECTED_UNIT_COST_POLICY_UNRESOLVED");
        var ranges=new ArrayList<CostAllocationEngine.ReceiptRange>();
        for(var c:claims) {
            if(c.accepted()!=basis||c.received()!=basis||c.price()==null
                ||c.price().compareTo(first.price())!=0||!first.currency().equals(c.currency())
                ||!"LOCAL_LINK_INTACT_UNVERIFIED".equals(c.state())
                ||!receipt.equals(c.rangeReceipt())||c.basis()==null||c.basis()!=basis
                ||c.from()==null||c.to()==null||c.quantity()<=0
                ||(long)c.to()-c.from()!=c.quantity())
                return hold("ORIGINAL_CLAIM_OR_RANGE_INCOMPLETE_OR_STALE");
            ranges.add(new CostAllocationEngine.ReceiptRange(c.id(),c.from(),c.to()));
        }
        var evaluated=ReceiptCostIntegrityGateV216.evaluate(marketplace,receipt,
            snapshot.sources(),snapshot.overlaps());
        var components=new ArrayList<Component>();
        var categories=new LinkedHashSet<String>();
        for(int i=0;i<evaluated.plans().size();i++) {
            var p=evaluated.plans().get(i);
            var s=snapshot.sources().get(i);
            if(!"LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED".equals(p.integrityState())
                ||!s.sourceId().toString().equals(p.sourceId())
                ||!first.currency().equals(p.originalCurrency()))
                return hold("RECEIPT_COST_SOURCE_NOT_INTACT");
            if(!List.of("INBOUND_FREIGHT","IMPORT_DUTY").contains(s.sourceCategory()))
                return hold("PAYMENT_FX_OR_OTHER_CAPITALIZATION_POLICY_UNRESOLVED");
            categories.add(s.sourceCategory());
            components.add(new Component(p.sourceId(),s.sourceCategory(),
                CostAllocationEngine.partitionReceiptAmount(p.localReceiptCandidateAmount(),
                    first.currency(),basis,ranges)));
        }
        if(!categories.containsAll(List.of("INBOUND_FREIGHT","IMPORT_DUTY")))
            return hold("FREIGHT_OR_DUTY_EVIDENCE_UNKNOWN_NOT_ZERO");
        // Partition EACH source separately. Never round a summed average unit
        // price; source-level micro-unit tails must remain reproducible.
        components.addFirst(new Component(null,"PURCHASE_PO_REFERENCE",
            CostAllocationEngine.partitionReceiptAmount(first.price().multiply(
                BigDecimal.valueOf(basis)).toPlainString(),first.currency(),basis,ranges)));
        BigDecimal total=BigDecimal.ZERO,allocated=BigDecimal.ZERO,remaining=BigDecimal.ZERO;
        var shares=new LinkedHashMap<UUID,BigDecimal>();
        for(var component:components) {
            var p=component.partition();
            total=total.add(new BigDecimal(p.sourceAmount()));
            allocated=allocated.add(new BigDecimal(p.allocatedAmount()));
            remaining=remaining.add(new BigDecimal(p.unallocatedAmount()));
            for(var share:p.shares())shares.merge(share.claimId(),
                new BigDecimal(share.candidateAmount()),BigDecimal::add);
        }
        if(total.compareTo(allocated.add(remaining))!=0)
            throw new IllegalArgumentException("Receipt candidate conservation failed");
        return new Candidate("LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED",first.currency(),
            total.toPlainString(),allocated.toPlainString(),remaining.toPlainString(),
            List.copyOf(components),shares.entrySet().stream().map(e->new SaleShare(
                e.getKey().toString(),e.getValue().toPlainString())).toList(),
            List.of("EXTERNAL_COST_COVERAGE_UNPROVEN","QC_DENOMINATOR_AND_G1_UNAPPROVED"),
            false,null,null);
    }
}
