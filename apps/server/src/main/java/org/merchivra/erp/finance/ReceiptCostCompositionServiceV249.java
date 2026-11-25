package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
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
    private final OrderLotAssignmentIntegrityServiceV203 orderLots;
    private final RefundRecoveryService recovery;
    private final TransactionTemplate read;
    private final TransactionTemplate captureTx;
    public ReceiptCostCompositionServiceV249(JdbcTemplate jdbc,
        ReceiptCostIntegrityServiceV216 audit,PlatformTransactionManager manager,
        OrderLotAssignmentIntegrityServiceV203 orderLots,RefundRecoveryService recovery) {
        this.jdbc=jdbc;this.audit=audit;this.orderLots=orderLots;this.recovery=recovery;
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
            return captureBasis(tenant,marketplace,receipt,claimId,readCandidate(tenant,marketplace,receipt));
    }

    private RefundOriginalCostSliceEngineV255.Basis captureBasis(
        UUID tenant,UUID marketplace,UUID receipt,UUID claimId,Candidate candidate) {
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

    record ReservedSlice(UUID event,int from,int to,boolean current) {}
    record RefundReservationRead(String state,String basisSha256,int reservedRows,
        RefundOriginalCostSliceEngineV255.Replay replay,List<String> blockers,
        RefundOriginalCostSliceEngineV255.Basis basis) {}

    // Same RR snapshot, never release stale reservations or post a reversal.
    RefundReservationRead readRefundReservations(UUID tenant,UUID marketplace,UUID receipt,UUID claimId) {
        if(tenant==null||marketplace==null||receipt==null||claimId==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return read.execute(ignored->readRefundReservationsSnapshot(
            tenant,marketplace,receipt,claimId,false,new HashMap<>()));
    }

    private RefundReservationRead readRefundReservationsSnapshot(UUID tenant,UUID marketplace,
        UUID receipt,UUID claimId,boolean allowEmpty,Map<UUID,Candidate> cache) {
            var stored=jdbc.query("""
                SELECT btrim(b.basis_sha256),b.canonical_payload
                FROM finance_original_cost_basis_v119 b
                JOIN finance_sale_lot_claim_v106 c ON c.tenant_id=b.tenant_id AND c.id=b.claim_id
                WHERE b.tenant_id=? AND c.marketplace_id=? AND c.receipt_id=? AND c.id=?
                """,(rs,n)->List.of(rs.getString(1),rs.getString(2)),tenant,marketplace,receipt,claimId);
            if(stored.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String digest=stored.getFirst().getFirst();
            var rows=jdbc.query("""
                SELECT s.refund_event_id,s.unit_from,s.unit_to,
                  coalesce(r.evidence_revision_v121=s.review_evidence_revision_v122
                    AND r.revision=s.review_revision AND r.reviewed_quantity=s.review_quantity
                    AND r.reviewed_at=s.review_at AND r.disposition='RESTOCKED_CLAIMED'
                    AND r.sales_order_id=c.sales_order_id
                    AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
                    AND f.category='PRODUCT_REFUND' AND f.amount<0
                    AND f.seller_marketplace_id=c.marketplace_id
                    AND l.state='MATCHED' AND l.order_id=c.sales_order_id
                    AND NOT EXISTS (
                      SELECT 1 FROM finance_refund_cost_slice_v119 peer
                      LEFT JOIN finance_sale_lot_integrity_v106 v
                        ON v.tenant_id=peer.tenant_id AND v.claim_id=peer.claim_id
                      WHERE peer.tenant_id=s.tenant_id AND peer.refund_event_id=s.refund_event_id
                        AND peer.order_item_id=s.order_item_id AND (
                          v.integrity_state IS DISTINCT FROM 'LOCAL_LINK_INTACT_UNVERIFIED'
                          OR peer.review_evidence_revision_v122 IS DISTINCT FROM s.review_evidence_revision_v122
                          OR peer.review_revision<>s.review_revision OR peer.review_quantity<>s.review_quantity
                          OR peer.review_at<>s.review_at))
                    AND (SELECT coalesce(sum(p.unit_to::bigint-p.unit_from),0)
                         FROM finance_refund_cost_slice_v119 p
                         WHERE p.tenant_id=s.tenant_id AND p.refund_event_id=s.refund_event_id
                           AND p.order_item_id=s.order_item_id)<=r.reviewed_quantity,false) AS current
                FROM finance_refund_cost_slice_v119 s
                JOIN finance_sale_lot_claim_v106 c ON c.tenant_id=s.tenant_id AND c.id=s.claim_id
                LEFT JOIN finance_refund_item_review r ON r.tenant_id=s.tenant_id
                  AND r.event_id=s.refund_event_id AND r.external_item_id=c.snapshot_external_item_id
                LEFT JOIN finance_source_event f ON f.tenant_id=s.tenant_id AND f.id=s.refund_event_id
                LEFT JOIN finance_event_link_review l ON l.tenant_id=f.tenant_id AND l.event_id=f.id
                WHERE s.tenant_id=? AND s.claim_id=? ORDER BY s.unit_from LIMIT 1001
                """,(rs,n)->new ReservedSlice(rs.getObject(1,UUID.class),rs.getInt(2),
                    rs.getInt(3),rs.getBoolean(4)),tenant,claimId);
            if((rows.isEmpty()&&!allowEmpty)||rows.size()>1000)
                return reservationHold(digest,rows.size(),"RESERVATION_POPULATION_EMPTY_OR_UNBOUNDED");
            if(rows.stream().anyMatch(s->!s.current()))
                return reservationHold(digest,rows.size(),"REFUND_REVIEW_OR_LINK_STALE");
            RefundOriginalCostSliceEngineV255.Basis fresh;
            try {
                fresh=captureBasis(tenant,marketplace,receipt,claimId,
                    cache.computeIfAbsent(receipt,key->readCandidate(tenant,marketplace,key)));
            } catch(ResponseStatusException invalid) {
                if(invalid.getStatusCode().value()!=409&&invalid.getStatusCode().value()!=404)throw invalid;
                return reservationHold(digest,rows.size(),"ORIGINAL_COST_CANDIDATE_NOT_CURRENT");
            }
            if(!digest.equals(fresh.sha256())||
                !stored.getFirst().get(1).equals(RefundOriginalCostSliceEngineV255.canonicalPayload(fresh)))
                return reservationHold(digest,rows.size(),"ORIGINAL_COST_FINGERPRINT_CHANGED");
            if(rows.isEmpty())
                return new RefundReservationRead("LOCAL_RESERVATIONS_EMPTY_UNVERIFIED",digest,0,null,
                    List.of("EXTERNAL_PHYSICAL_RETURN_AND_G1_UNVERIFIED"),fresh);
            try {
                var slices=rows.stream().map(s->new RefundOriginalCostSliceEngineV255.Slice(
                    s.event(),fresh.identity().orderItemId(),s.from(),s.to(),s.to()-s.from(),
                    "RESTOCKED_CLAIMED")).toList();
                var replay=RefundOriginalCostSliceEngineV255.replay(fresh.identity(),digest,fresh,slices);
                return new RefundReservationRead("LOCAL_RESERVATIONS_CURRENT_UNVERIFIED",digest,
                    rows.size(),replay,replay.blockerCodes(),fresh);
            } catch(IllegalArgumentException invalid) {
                return reservationHold(digest,rows.size(),"RESERVATION_REPLAY_INCONSISTENT");
            }
    }
    private static RefundReservationRead reservationHold(String digest,int rows,String reason) {
        return new RefundReservationRead("LOCAL_RESERVATIONS_HOLD",digest,rows,null,
            List.of(reason,"EXTERNAL_PHYSICAL_RETURN_AND_G1_UNVERIFIED"),null);
    }

    // V265: internal DRY RUN for a same-coordinate re-attestation, NOT a
    // reversal or replacement. The old immutable reservation remains active
    // as a capacity fence and stale as cost evidence. No release/write path.
    record CompensationSource(UUID order,String externalItem,int reviewedQuantity,
        long version,String currency,BigDecimal amount) {}
    record CompensationPeer(UUID claim,UUID receipt,UUID marketplace,UUID order,
        String externalItem,String basisDigest,String payload,int from,int to,
        Long oldVersion,int oldQuantity) {}
    record CompensationCoordinate(String claimId,String receiptId,String basisSha256,
        int fromInclusive,int toExclusive,long previousEvidenceVersion,String currency,
        String candidateAmount) {}
    record CompensationPreview(String state,String proposalSha256,String eventId,
        String orderItemId,long currentEvidenceVersion,int reviewRevision,
        List<CompensationCoordinate> coordinates,List<String> blockers,
        boolean releaseAllowed,boolean replacementAllowed,String actualRefundCogsReversal,
        String realizedNetProfit) {}
    private static ResponseStatusException compensationConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Same-coordinate refund re-attestation unavailable");
    }
    CompensationPreview previewRefundCompensation(UUID tenant,UUID marketplace,
        UUID event,UUID orderItem) {
        if(tenant==null||marketplace==null||event==null||orderItem==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return read.execute(ignored->compensationSnapshot(tenant,marketplace,event,orderItem).preview());
    }

    private record CompensationSnapshot(CompensationPreview preview,UUID order,
        String payload,String reviewDigest,RefundRecoveryService.Status review) {}

    private CompensationSnapshot compensationSnapshot(UUID tenant,UUID marketplace,
        UUID event,UUID orderItem) {
            var sources=jdbc.query("""
                SELECT o.id,i.external_item_id,r.reviewed_quantity,r.evidence_revision_v121,
                  f.currency_code,f.amount
                FROM order_item i
                JOIN sales_order o ON o.tenant_id=i.tenant_id AND o.id=i.sales_order_id
                JOIN tenant t ON t.id=o.tenant_id AND t.status='ACTIVE'
                JOIN finance_refund_item_review r ON r.tenant_id=i.tenant_id
                  AND r.sales_order_id=o.id AND r.external_item_id=i.external_item_id
                JOIN finance_source_event f ON f.tenant_id=r.tenant_id AND f.id=r.event_id
                JOIN finance_event_link_review l ON l.tenant_id=f.tenant_id AND l.event_id=f.id
                WHERE i.tenant_id=? AND i.id=? AND f.id=? AND o.seller_marketplace_id=?
                  AND f.seller_marketplace_id=o.seller_marketplace_id AND o.data_origin='LIVE'
                  AND i.data_origin='LIVE' AND i.is_current=true AND i.quantity>=r.reviewed_quantity
                  AND r.disposition='RESTOCKED_CLAIMED' AND f.source_family='SPAPI_FINANCES'
                  AND f.data_origin='LIVE' AND f.category='PRODUCT_REFUND' AND f.amount<0
                  AND l.state='MATCHED' AND l.order_id=o.id
                  AND (f.external_order_id IS NULL OR f.external_order_id=o.external_order_id)
                """,(rs,n)->new CompensationSource(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getInt(3),rs.getLong(4),rs.getString(5).trim(),rs.getBigDecimal(6)),
                    tenant,orderItem,event,marketplace);
            if(sources.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var source=sources.getFirst();
            var peers=jdbc.query("""
                SELECT c.id,c.receipt_id,c.marketplace_id,c.sales_order_id,
                  c.snapshot_external_item_id,btrim(s.basis_sha256),b.canonical_payload,
                  s.unit_from,s.unit_to,s.review_evidence_revision_v122,s.review_quantity
                FROM finance_refund_cost_slice_v119 s
                JOIN finance_sale_lot_claim_v106 c ON c.tenant_id=s.tenant_id AND c.id=s.claim_id
                JOIN finance_original_cost_basis_v119 b ON b.tenant_id=s.tenant_id AND b.claim_id=s.claim_id
                  AND b.basis_sha256=s.basis_sha256
                WHERE s.tenant_id=? AND s.refund_event_id=? AND s.order_item_id=?
                ORDER BY c.id LIMIT 51
                """,(rs,n)->new CompensationPeer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5),
                    rs.getString(6),rs.getString(7),rs.getInt(8),rs.getInt(9),
                    rs.getObject(10,Long.class),rs.getInt(11)),tenant,event,orderItem);
            if(peers.isEmpty()||peers.size()>50||
                peers.stream().map(CompensationPeer::receipt).distinct().count()>20)
                throw compensationConflict();
            long units=0;
            var oldVersion=peers.getFirst().oldVersion();
            if(oldVersion==null||oldVersion<=0||oldVersion>=source.version())throw compensationConflict();
            var seen=new HashSet<UUID>();
            for(var peer:peers) {
                if(!seen.add(peer.claim())||!marketplace.equals(peer.marketplace())||
                    !source.order().equals(peer.order())||!source.externalItem().equals(peer.externalItem())||
                    !oldVersion.equals(peer.oldVersion())||peer.oldQuantity()!=source.reviewedQuantity()||
                    peer.from()<0||peer.to()<=peer.from())throw compensationConflict();
                units+=(long)peer.to()-peer.from();
            }
            if(units!=source.reviewedQuantity())throw compensationConflict();
            var review=recovery.preview(tenant,marketplace,event);
            if(!RefundCogsReversalGateV197.assess(event,review,source.order(),
                    source.currency(),source.amount()).sourceSnapshotConsistent()||
                !recovery.currentIndependentAudit(tenant,marketplace,event,review)||
                review.exposure()==null||!"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(review.exposure().status())||
                review.exposure().items().stream().filter(i->source.externalItem().equals(i.itemId())
                    &&i.refundedQuantity()==source.reviewedQuantity()
                    &&"RESTOCKED_CLAIMED".equals(i.disposition())).count()!=1)
                throw compensationConflict();
            var lineage=orderLots.preview(tenant,marketplace,source.order());
            if(!"COGS_READY_PREVIEW".equals(lineage.orderCostReadiness()))throw compensationConflict();
            var cache=new HashMap<UUID,Candidate>();
            var coordinates=new ArrayList<CompensationCoordinate>();
            for(var peer:peers) {
                if(lineage.claims().stream().filter(l->peer.claim().toString().equals(l.claimId())
                    &&peer.receipt().toString().equals(l.purchaseReceiptId())
                    &&source.externalItem().equals(l.externalItemId())
                    &&"LOCAL_LINK_INTACT_UNVERIFIED".equals(l.integrityState())).count()!=1)
                    throw compensationConflict();
                var basis=captureBasis(tenant,marketplace,peer.receipt(),peer.claim(),
                    cache.computeIfAbsent(peer.receipt(),key->readCandidate(tenant,marketplace,key)));
                if(!orderItem.equals(basis.identity().orderItemId())||
                    !peer.basisDigest().equals(basis.sha256())||
                    !peer.payload().equals(RefundOriginalCostSliceEngineV255.canonicalPayload(basis)))
                    throw compensationConflict();
                var replay=RefundOriginalCostSliceEngineV255.replay(basis.identity(),basis.sha256(),basis,
                    List.of(new RefundOriginalCostSliceEngineV255.Slice(event,orderItem,peer.from(),peer.to(),
                        peer.to()-peer.from(),"RESTOCKED_CLAIMED")));
                coordinates.add(new CompensationCoordinate(peer.claim().toString(),peer.receipt().toString(),
                    peer.basisDigest(),peer.from(),peer.to(),oldVersion,basis.currency(),replay.refundCandidateAmount()));
            }
            // Deterministic content ID only, not an approval or write token.
            var canonical=new StringBuilder("V265_SAME_COORDINATE_DRY_RUN|").append(tenant).append('|')
                .append(marketplace).append('|').append(source.order()).append('|').append(event).append('|')
                .append(orderItem).append('|').append(source.version()).append('|').append(review.revision());
            for(var c:coordinates)canonical.append('|').append(c.claimId()).append('|').append(c.receiptId())
                .append('|').append(c.basisSha256()).append('|').append(c.fromInclusive()).append('|')
                .append(c.toExclusive()).append('|').append(c.previousEvidenceVersion()).append('|')
                .append(c.currency()).append('|').append(c.candidateAmount());
            var digest=jdbc.queryForObject("""
                SELECT btrim(source_evidence_sha256) FROM finance_refund_recovery_review
                WHERE tenant_id=? AND seller_marketplace_id=? AND event_id=? AND revision=?
                """,String.class,tenant,marketplace,event,review.revision());
            var payload=canonical.toString();
            var preview=new CompensationPreview("LOCAL_SAME_COORDINATE_COMPENSATION_PROPOSAL_UNVERIFIED",
                RefundRecoveryService.hash(payload),event.toString(),orderItem.toString(),
                source.version(),review.revision(),List.copyOf(coordinates),
                List.of("COMPENSATION_ACTIVATION_NOT_IMPLEMENTED",
                    "EXTERNAL_PHYSICAL_RETURN_AND_G1_UNVERIFIED"),false,false,null,null);
            return new CompensationSnapshot(preview,source.order(),payload,digest,review);
    }

    // V266: append-only internal proposal/approval. Never consumed by the
    // reservation read or capacity accounting; no activation/write HTTP route.
    record CompensationReceipt(String proposalSha256,String state,String requestedBy,
        String approvedBy,boolean releaseAllowed,boolean replacementAllowed,
        String actualRefundCogsReversal,String realizedNetProfit) {}
    private record CompensationRequest(UUID actor,String reason) {}
    private record CompensationDecision(UUID actor,String reason) {}
    private static void compensationInput(UUID tenant,UUID marketplace,UUID event,
        UUID item,UUID actor,String expected) {
        if(tenant==null||marketplace==null||event==null||item==null||actor==null
            ||expected==null||!expected.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private CompensationRequest currentRequest(UUID tenant,UUID marketplace,UUID event,
        UUID item,CompensationSnapshot snapshot) {
        var rows=jdbc.query("""
            SELECT requested_by,request_reason FROM finance_refund_compensation_proposal_v123 p
            WHERE p.tenant_id=? AND p.marketplace_id=? AND p.event_id=? AND p.order_item_id=?
              AND p.sales_order_id=? AND p.proposal_sha256=? AND p.canonical_payload=?
              AND p.review_revision=? AND p.review_sha256=?
            """,(rs,n)->new CompensationRequest(rs.getObject(1,UUID.class),rs.getString(2)),
            tenant,marketplace,event,item,snapshot.order(),snapshot.preview().proposalSha256(),
            snapshot.payload(),snapshot.review().revision(),snapshot.reviewDigest());
        if(rows.size()!=1)throw compensationConflict();
        return rows.getFirst();
    }
    private CompensationDecision storedDecision(UUID tenant,String expected) {
        var rows=jdbc.query("""
            SELECT approved_by,approval_reason FROM finance_refund_compensation_approval_v123
            WHERE tenant_id=? AND proposal_sha256=?
            """,(rs,n)->new CompensationDecision(rs.getObject(1,UUID.class),rs.getString(2)),tenant,expected);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static CompensationReceipt compensationReceipt(String expected,CompensationRequest request,
        CompensationDecision decision) {
        return new CompensationReceipt(expected,decision==null?"LOCAL_PROPOSAL_RECORDED":
            "LOCAL_APPROVAL_RECORDED_NOT_ACTIVATED",request.actor().toString(),
            decision==null?null:decision.actor().toString(),false,false,null,null);
    }

    CompensationReceipt recordRefundCompensation(UUID tenant,UUID marketplace,UUID event,
        UUID item,String expected,UUID actor,String reason) {
        compensationInput(tenant,marketplace,event,item,actor,expected);
        String note=RefundRecoveryService.reason(reason);
        return captureTx.execute(ignored->{
            recovery.requireCurrentOwner(tenant,actor);
            var snapshot=compensationSnapshot(tenant,marketplace,event,item);
            if(!expected.equals(snapshot.preview().proposalSha256()))throw compensationConflict();
            jdbc.update("""
                INSERT INTO finance_refund_compensation_proposal_v123
                  (tenant_id,marketplace_id,sales_order_id,event_id,order_item_id,proposal_sha256,
                   canonical_payload,review_revision,review_sha256,requested_by,request_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,proposal_sha256) DO NOTHING
                """,tenant,marketplace,snapshot.order(),event,item,expected,snapshot.payload(),
                snapshot.review().revision(),snapshot.reviewDigest(),actor,note);
            var request=currentRequest(tenant,marketplace,event,item,snapshot);
            if(!actor.equals(request.actor())||!note.equals(request.reason()))throw compensationConflict();
            return compensationReceipt(expected,request,storedDecision(tenant,expected));
        });
    }

    CompensationReceipt approveRefundCompensation(UUID tenant,UUID marketplace,UUID event,
        UUID item,String expected,UUID actor,String reason) {
        compensationInput(tenant,marketplace,event,item,actor,expected);
        String note=RefundRecoveryService.reason(reason);
        return captureTx.execute(ignored->{
            recovery.requireCurrentOwner(tenant,actor);
            var snapshot=compensationSnapshot(tenant,marketplace,event,item);
            if(!expected.equals(snapshot.preview().proposalSha256()))throw compensationConflict();
            var request=currentRequest(tenant,marketplace,event,item,snapshot);
            recovery.requireCurrentOwner(tenant,request.actor());
            RefundRecoveryService.requireIndependent(request.actor(),actor);
            if(actor.toString().equals(snapshot.review().decidedBy())
                ||actor.toString().equals(snapshot.review().requestedBy()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Compensation approver must differ from both V63 review actors");
            recovery.checkIndependentAuthors(tenant,event,actor);
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM finance_refund_compensation_proposal_v123 p
                  JOIN local_team_audit a ON a.tenant_id=p.tenant_id
                    AND a.subject_user_id=p.requested_by AND a.event_code='OWNER_REVOKED'
                    AND a.occurred_at>=p.requested_at
                  WHERE p.tenant_id=? AND p.proposal_sha256=?)
                """,Boolean.class,tenant,expected)))throw compensationConflict();
            jdbc.update("""
                INSERT INTO finance_refund_compensation_approval_v123
                  (tenant_id,proposal_sha256,requested_by,approved_by,approval_reason)
                VALUES (?,?,?,?,?) ON CONFLICT (tenant_id,proposal_sha256) DO NOTHING
                """,tenant,expected,request.actor(),actor,note);
            var decision=storedDecision(tenant,expected);
            if(decision==null||!actor.equals(decision.actor())||!note.equals(decision.reason()))
                throw compensationConflict();
            return compensationReceipt(expected,request,decision);
        });
    }

    record OrderItem(UUID id,String externalId,Integer quantity) {}
    record OrderClaim(UUID id,UUID item,UUID receipt,UUID marketplace,int quantity) {}
    record OrderRefundEvent(UUID id,UUID marketplace,UUID linkedOrder,String linkState,
        BigDecimal amount,String externalOrderId,String currency) {}
    record OrderRefundAllocation(UUID event,String item,BigDecimal amount,String currency) {}
    record OrderRefundReview(UUID event,String item,int quantity,String disposition) {}
    record EventItem(UUID event,UUID item) {}
    record OrderRefundCurrency(String currency,String originalSaleCandidateCost,
        String refundCandidateCost,String remainingOriginalCandidateCost) {}
    record OrderRefundRead(String state,String orderId,String marketplaceId,
        int originalClaimRows,int sourceRefundEvents,long reviewedRefundUnits,
        long reservedRefundUnits,List<OrderRefundCurrency> currencies,List<String> blockers,
        boolean refundCogsReversalAllowed,String actualRefundCogsReversal,String realizedNetProfit) {}
    private static final class CurrencySum {
        BigDecimal original=BigDecimal.ZERO.setScale(6);
        BigDecimal refund=BigDecimal.ZERO.setScale(6);
    }
    private static OrderRefundRead orderHold(UUID order,UUID market,String reason) {
        return new OrderRefundRead("LOCAL_ORDER_REFUND_COST_HOLD",order.toString(),market.toString(),
            0,0,0,0,List.of(),List.of(reason,"EXTERNAL_REFUND_COVERAGE_AND_G1_UNVERIFIED"),false,null,null);
    }

    // V258: no HTTP/write entry point. One RR for the entire bounded local
    // order population. All current sale items must be covered, even if an
    // item has not been refunded. No FX conversion or incomplete totals.
    OrderRefundRead readWholeOrderRefundReservations(UUID tenant,UUID marketplace,UUID order) {
        if(tenant==null||marketplace==null||order==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return read.execute(ignored->{
            var scope=jdbc.queryForList("""
                SELECT o.external_order_id FROM sales_order o
                JOIN tenant t ON t.id=o.tenant_id AND t.status='ACTIVE'
                JOIN seller_marketplace m ON m.tenant_id=o.tenant_id AND m.id=o.seller_marketplace_id
                WHERE o.tenant_id=? AND o.seller_marketplace_id=? AND o.id=? AND o.data_origin='LIVE'
                """,String.class,tenant,marketplace,order);
            if(scope.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String external=scope.getFirst();
            if(external==null||external.isBlank())return orderHold(order,marketplace,"ORIGINAL_ORDER_IDENTITY_UNKNOWN");
            var items=jdbc.query("""
                SELECT id,external_item_id,quantity FROM order_item
                WHERE tenant_id=? AND sales_order_id=? AND is_current=true AND data_origin='LIVE'
                ORDER BY id LIMIT 51
                """,(rs,n)->new OrderItem(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getObject(3,Integer.class)),tenant,order);
            var claims=jdbc.query("""
                SELECT id,order_item_id,receipt_id,marketplace_id,assigned_quantity
                FROM finance_sale_lot_claim_v106 WHERE tenant_id=? AND sales_order_id=?
                ORDER BY id LIMIT 51
                """,(rs,n)->new OrderClaim(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getInt(5)),tenant,order);
            if(items.isEmpty()||items.size()>50||claims.isEmpty()||claims.size()>50||
                claims.stream().map(OrderClaim::receipt).distinct().count()>20)
                return orderHold(order,marketplace,"ORDER_ITEM_OR_COST_CLAIM_POPULATION_MISSING_OR_UNBOUNDED");
            var byExternal=new HashMap<String,OrderItem>();
            var sold=new HashMap<UUID,Long>();
            for(var i:items) {
                if(i.id()==null||i.externalId()==null||i.externalId().isBlank()||
                    i.quantity()==null||i.quantity()<=0||
                    byExternal.putIfAbsent(i.externalId(),i)!=null||
                    sold.putIfAbsent(i.id(),i.quantity().longValue())!=null)
                    return orderHold(order,marketplace,"ORIGINAL_ORDER_ITEM_IDENTITY_OR_QUANTITY_INVALID");
            }
            var attributed=new HashMap<UUID,Long>();
            for(var c:claims) {
                if(!marketplace.equals(c.marketplace())||!sold.containsKey(c.item())||c.quantity()<=0)
                    return orderHold(order,marketplace,"ORIGINAL_ORDER_COST_CLAIM_SCOPE_INVALID");
                attributed.merge(c.item(),(long)c.quantity(),Long::sum);
            }
            if(!sold.equals(attributed))
                return orderHold(order,marketplace,"ORIGINAL_ORDER_COST_CLAIM_QUANTITY_INCOMPLETE");

            // V260: reuse the V203 effective-state gate (V29 date-valid SKU
            // and cost readiness + V106). REQUIRED propagation joins this RR;
            // never substitute a receipt SKU for a stale order-time mapping.
            var lineage=orderLots.preview(tenant,marketplace,order);
            if(!"COGS_READY_PREVIEW".equals(lineage.orderCostReadiness())||
                lineage.claims().size()!=claims.size())
                return orderHold(order,marketplace,"ORIGINAL_ORDER_HISTORICAL_SKU_COST_NOT_READY");
            var claimById=new HashMap<String,OrderClaim>();
            for(var c:claims)claimById.put(c.id().toString(),c);
            var lineageIds=new HashSet<String>();
            for(var link:lineage.claims()) {
                var c=claimById.get(link.claimId());
                var item=byExternal.get(link.externalItemId());
                if(c==null||item==null||!lineageIds.add(link.claimId())||
                    !c.item().equals(item.id())||!c.receipt().toString().equals(link.purchaseReceiptId())||
                    c.quantity()!=link.assignedQuantity()||
                    !"LOCAL_LINK_INTACT_UNVERIFIED".equals(link.integrityState()))
                    return orderHold(order,marketplace,"ORIGINAL_ORDER_SALE_SKU_OR_LOT_LINEAGE_STALE");
            }

            // Include unmatched exact external-order references as blockers,
            // not just the already matched rows. Unknown external refs remain
            // a genuine coverage limitation, not an inferred zero.
            var events=jdbc.query("""
                SELECT f.id,f.seller_marketplace_id,l.order_id,l.state,f.amount,f.external_order_id,f.currency_code
                FROM finance_source_event f LEFT JOIN finance_event_link_review l
                  ON l.tenant_id=f.tenant_id AND l.event_id=f.id
                WHERE f.tenant_id=? AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
                  AND f.category='PRODUCT_REFUND'
                  AND (l.order_id=? OR (f.seller_marketplace_id=? AND f.external_order_id=?))
                ORDER BY f.id LIMIT 1001
                """,(rs,n)->new OrderRefundEvent(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getObject(3,UUID.class),rs.getString(4),rs.getBigDecimal(5),rs.getString(6),rs.getString(7)),
                    tenant,order,marketplace,external);
            var reviews=jdbc.query("""
                SELECT event_id,external_item_id,reviewed_quantity,disposition
                FROM finance_refund_item_review WHERE tenant_id=? AND sales_order_id=?
                ORDER BY event_id,external_item_id LIMIT 1001
                """,(rs,n)->new OrderRefundReview(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getInt(3),rs.getString(4)),tenant,order);
            if(events.isEmpty()||events.size()>1000||reviews.isEmpty()||reviews.size()>1000)
                return orderHold(order,marketplace,"LOCAL_REFUND_EVENT_OR_REVIEW_POPULATION_MISSING_OR_UNBOUNDED");
            var sourceIds=new HashSet<UUID>();
            for(var e:events) {
                if(e.id()==null||!sourceIds.add(e.id())||!marketplace.equals(e.marketplace())||
                    !order.equals(e.linkedOrder())||!"MATCHED".equals(e.linkState())||
                    e.amount()==null||e.amount().signum()>=0||e.currency()==null||
                    !e.currency().trim().matches("[A-Z]{3}")||
                    (e.externalOrderId()!=null&&!external.equals(e.externalOrderId())))
                    return orderHold(order,marketplace,"LOCAL_REFUND_EVENT_LINK_OR_AMOUNT_INVALID");
            }
            var expected=new HashMap<EventItem,Long>();
            var reviewedPerItem=new HashMap<UUID,Long>();
            var reviewedEvents=new HashSet<UUID>();
            for(var review:reviews) {
                var item=byExternal.get(review.item());
                if(item==null||!sourceIds.contains(review.event())||review.quantity()<=0||
                    !"RESTOCKED_CLAIMED".equals(review.disposition())||
                    expected.putIfAbsent(new EventItem(review.event(),item.id()),(long)review.quantity())!=null)
                    return orderHold(order,marketplace,"LOCAL_REFUND_REVIEW_OR_DISPOSITION_UNRESOLVED");
                reviewedEvents.add(review.event());
                if(reviewedPerItem.merge(item.id(),(long)review.quantity(),Long::sum)>sold.get(item.id()))
                    return orderHold(order,marketplace,"CROSS_EVENT_REFUND_QUANTITY_EXCEEDS_SALE");
            }
            if(!reviewedEvents.equals(sourceIds))
                return orderHold(order,marketplace,"REFUND_EVENT_REVIEW_COVERAGE_INCOMPLETE");
            // V259: V34 quantity coverage alone cannot establish a balanced
            // refund. Reuse the V35 signed amount engine against every locally
            // identified raw refund event, without claiming V63 or classification.
            var allocations=jdbc.query("""
                SELECT a.event_id,a.external_item_id,a.signed_amount,a.currency_code
                FROM finance_refund_amount_allocation a
                JOIN finance_source_event f ON f.tenant_id=a.tenant_id AND f.id=a.event_id
                LEFT JOIN finance_event_link_review l ON l.tenant_id=f.tenant_id AND l.event_id=f.id
                WHERE f.tenant_id=? AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
                  AND f.category='PRODUCT_REFUND'
                  AND (l.order_id=? OR (f.seller_marketplace_id=? AND f.external_order_id=?))
                ORDER BY a.event_id,a.external_item_id LIMIT 1001
                """,(rs,n)->new OrderRefundAllocation(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getBigDecimal(3),rs.getString(4)),
                    tenant,order,marketplace,external);
            if(allocations.size()>1000)
                return orderHold(order,marketplace,"REFUND_AMOUNT_ALLOCATION_POPULATION_UNBOUNDED");
            var amountKeys=new HashSet<EventItem>();
            var byEvent=new HashMap<UUID,List<RefundAmountAllocationEngine.Entry>>();
            for(var a:allocations) {
                var item=byExternal.get(a.item());
                if(item==null||!sourceIds.contains(a.event())||a.currency()==null||
                    !amountKeys.add(new EventItem(a.event(),item.id())))
                    return orderHold(order,marketplace,"REFUND_AMOUNT_ALLOCATION_SCOPE_INVALID");
                byEvent.computeIfAbsent(a.event(),key->new ArrayList<>()).add(
                    new RefundAmountAllocationEngine.Entry(a.item(),a.amount(),a.currency().trim()));
            }
            if(!amountKeys.equals(expected.keySet()))
                return orderHold(order,marketplace,"REFUND_AMOUNT_ITEM_COVERAGE_INCOMPLETE");
            for(var e:events) {
                try {
                    var summary=RefundAmountAllocationEngine.evaluate(e.amount(),e.currency().trim(),
                        true,byEvent.getOrDefault(e.id(),List.of()));
                    if(!"SIGNED_TOTAL_MATCHES_UNVERIFIED".equals(summary.status()))
                        return orderHold(order,marketplace,"REFUND_AMOUNT_NOT_BALANCED_IN_ORIGINAL_CURRENCY");
                } catch(IllegalArgumentException invalid) {
                    return orderHold(order,marketplace,"REFUND_AMOUNT_NOT_BALANCED_IN_ORIGINAL_CURRENCY");
                }
            }
            var cache=new HashMap<UUID,Candidate>();
            var reserved=new HashMap<EventItem,Long>();
            var sums=new java.util.TreeMap<String,CurrencySum>();
            int totalRows=0;
            for(var c:claims) {
                RefundReservationRead row;
                try {
                    row=readRefundReservationsSnapshot(tenant,marketplace,c.receipt(),c.id(),true,cache);
                } catch(ResponseStatusException unavailable) {
                    if(unavailable.getStatusCode().value()!=404&&unavailable.getStatusCode().value()!=409)throw unavailable;
                    return orderHold(order,marketplace,"ORIGINAL_COST_BASIS_MISSING_OR_CONFLICT");
                }
                if(row.basis()==null||row.basis().originalTo()-row.basis().originalFrom()!=c.quantity()||
                    !c.item().equals(row.basis().identity().orderItemId())||
                    !order.equals(row.basis().identity().salesOrderId()))
                    return orderHold(order,marketplace,"ORIGINAL_COST_BASIS_OR_RESERVATION_STALE");
                totalRows+=row.reservedRows();
                if(totalRows>1000)return orderHold(order,marketplace,"ORDER_REFUND_RESERVATIONS_UNBOUNDED");
                var bucket=sums.computeIfAbsent(row.basis().currency(),key->new CurrencySum());
                bucket.original=bucket.original.add(new BigDecimal(row.basis().originalCandidateAmount()));
                if(row.replay()!=null) {
                    bucket.refund=bucket.refund.add(new BigDecimal(row.replay().refundCandidateAmount()));
                    for(var event:row.replay().events())
                        reserved.merge(new EventItem(event.refundEventId(),c.item()),(long)event.quantity(),Long::sum);
                }
            }
            if(!expected.equals(reserved))
                return orderHold(order,marketplace,"REFUND_EVENT_ITEM_RESERVATION_COVERAGE_INCOMPLETE");
            // V261: expensive V63 recomputations are explicitly bounded.
            // Join the same RR; propagate business conflicts rather than catch
            // a participating transactional exception and return a false result.
            if(events.size()>100)
                return orderHold(order,marketplace,"REFUND_INDEPENDENT_REVIEW_POPULATION_UNBOUNDED");
            for(var e:events) {
                var review=recovery.preview(tenant,marketplace,e.id());
                var gate=RefundCogsReversalGateV197.assess(e.id(),review,order,e.currency().trim(),e.amount());
                if(!gate.sourceSnapshotConsistent()||
                    !"REVIEWED".equals(review.state())||!review.evidenceCurrent()||
                    !"MANUAL_UNVERIFIED".equals(review.evidenceOrigin())||
                    review.exposure()==null||
                    !"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(review.exposure().status())||
                    !recovery.currentIndependentAudit(tenant,marketplace,e.id(),review))
                    return orderHold(order,marketplace,"REFUND_INDEPENDENT_REVIEW_MISSING_STALE_OR_CONFLICTING");
            }
            if(sums.values().stream().anyMatch(s->s.refund.signum()<0||s.refund.compareTo(s.original)>0))
                return orderHold(order,marketplace,"ORIGINAL_CURRENCY_COST_CONSERVATION_BROKEN");
            var currencies=sums.entrySet().stream().map(e->new OrderRefundCurrency(e.getKey(),
                e.getValue().original.toPlainString(),e.getValue().refund.toPlainString(),
                e.getValue().original.subtract(e.getValue().refund).toPlainString())).toList();
            long quantity=expected.values().stream().mapToLong(Long::longValue).sum();
            return new OrderRefundRead("LOCAL_ORDER_REFUND_COST_CANDIDATE_UNVERIFIED",
                order.toString(),marketplace.toString(),claims.size(),events.size(),quantity,quantity,currencies,
                List.of("EXTERNAL_REFUND_POPULATION_UNVERIFIED","ORIGINAL_SALE_TIME_COST_UNVERIFIED",
                    "EXTERNAL_PHYSICAL_RETURN_AND_G1_UNVERIFIED"),false,null,null);
        });
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
