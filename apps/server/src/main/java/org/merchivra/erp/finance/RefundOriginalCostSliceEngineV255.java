package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * V255: replay explicit refund subranges against ONE original local candidate
 * observation. No DB write, no automatic lot selection, no sale-time accounting
 * proof. The digest detects different contents, not authenticity or freshness.
 */
public final class RefundOriginalCostSliceEngineV255 {
    private RefundOriginalCostSliceEngineV255() {}
    public static final String VERSION="V255_LOCAL_SALE_COST_BASIS_V1";
    public record Identity(UUID tenantId,UUID marketplaceId,UUID receiptId,UUID claimId,
        UUID salesOrderId,UUID orderItemId) {}
    public record Source(String category,UUID sourceId,String receiptAmount,
        String originalClaimAmount) {}
    public record Basis(String version,Identity identity,String currency,int basisQuantity,
        int originalFrom,int originalTo,List<Source> sources,String originalCandidateAmount,
        String sha256) {
        public Basis { sources=List.copyOf(sources); }
    }
    /** Explicit original coordinates; local restock claim is NOT physical proof.
     * Each event may have at most one contiguous slice per original claim.
     * Caller supplies the WHOLE proposed event population, not just latest refund.
     */
    public record Slice(UUID refundEventId,UUID orderItemId,int fromInclusive,int toExclusive,
        int reviewedQuantity,String disposition) {}
    public record EventCandidate(UUID refundEventId,int quantity,String candidateAmount) {}
    public record Replay(String state,String basisSha256,String currency,
        int originalQuantity,int candidateQuantity,int remainingQuantity,
        String originalCandidateAmount,String refundCandidateAmount,
        String remainingCandidateAmount,List<EventCandidate> events,
        List<String> blockerCodes,boolean refundCogsReversalAllowed,
        String actualRefundCogsReversal,String realizedNetProfit) {}

    private static void require(boolean valid,String reason) {
        if(!valid)throw new IllegalArgumentException(reason);
    }
    private static void identity(Identity i) {
        require(i!=null&&i.tenantId()!=null&&i.marketplaceId()!=null&&i.receiptId()!=null
            &&i.claimId()!=null&&i.salesOrderId()!=null&&i.orderItemId()!=null,"Missing original scope");
    }
    private static BigDecimal money(String v) {
        require(v!=null&&v.matches("[0-9]{1,16}\\.[0-9]{6}"),"Invalid canonical amount");
        return new BigDecimal(v);
    }
    private static BigDecimal zero(){return BigDecimal.ZERO.setScale(6);}
    private static final Comparator<Source> ORDER=Comparator.comparing(Source::category)
        .thenComparing(s->s.sourceId()==null?"":s.sourceId().toString());

    static Basis capture(Identity id,ReceiptCostCompositionServiceV249.Candidate candidate) {
        identity(id);
        require(candidate!=null&&"LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED".equals(candidate.state())
            &&!candidate.actualBatchCogsVerified()&&candidate.actualBatchCogs()==null
            &&candidate.realizedNetProfit()==null&&candidate.components()!=null
            &&candidate.components().size()>=3&&candidate.components().size()<=51
            &&candidate.saleShares()!=null,"Local original cost candidate unavailable");
        var sources=new ArrayList<Source>();
        int basis=0,from=-1,to=-1;
        BigDecimal sum=zero();
        for(var component:candidate.components()) {
            require(component!=null&&component.partition()!=null,"Missing cost component");
            var p=component.partition();
            require(p.currency().equals(candidate.currency()),"Mixed original currency");
            var matches=p.shares().stream().filter(s->id.claimId().equals(s.claimId())).toList();
            require(matches.size()==1,"Original claim missing or duplicated");
            var share=matches.getFirst();
            if(basis==0){basis=p.basisQuantity();from=share.fromInclusive();to=share.toExclusive();}
            require(basis==p.basisQuantity()&&from==share.fromInclusive()&&to==share.toExclusive(),
                "Original range differs between components");
            sources.add(new Source(component.category(),
                component.sourceId()==null?null:UUID.fromString(component.sourceId()),
                p.sourceAmount(),share.candidateAmount()));
            sum=sum.add(money(share.candidateAmount()));
        }
        var sale=candidate.saleShares().stream().filter(s->id.claimId().toString().equals(s.claimId())).toList();
        require(sale.size()==1&&sum.compareTo(money(sale.getFirst().candidateCost()))==0,
            "Original sale candidate mismatch");
        sources.sort(ORDER);
        var raw=new Basis(VERSION,id,candidate.currency(),basis,from,to,sources,sum.toPlainString(),null);
        validate(raw);
        return new Basis(raw.version(),id,raw.currency(),basis,from,to,sources,raw.originalCandidateAmount(),digest(raw));
    }

    private static BigDecimal slice(Source s,Basis b,UUID key,int from,int to) {
        // Use the ORIGINAL receipt prefix difference. Repartitioning the
        // rounded sale subtotal itself would misattribute micro-unit tails.
        var p=CostAllocationEngine.partitionReceiptAmount(s.receiptAmount(),b.currency(),
            b.basisQuantity(),List.of(new CostAllocationEngine.ReceiptRange(key,from,to)));
        return money(p.allocatedAmount());
    }
    private static void validate(Basis b) {
        require(b!=null&&VERSION.equals(b.version()),"Unknown basis version");
        identity(b.identity());
        require(b.currency()!=null&&b.currency().matches("[A-Z]{3}")&&b.basisQuantity()>0
            &&b.originalFrom()>=0&&b.originalTo()>b.originalFrom()&&b.originalTo()<=b.basisQuantity()
            &&b.sources().size()>=3&&b.sources().size()<=51,"Invalid original basis/range");
        var ids=new HashSet<UUID>();
        int po=0,freight=0,duty=0;
        BigDecimal total=zero();
        for(var s:b.sources()) {
            require(s!=null&&s.category()!=null,"Invalid original source");
            switch(s.category()){
                case "PURCHASE_PO_REFERENCE" -> {require(s.sourceId()==null,"PO source must be reference");po++;}
                case "INBOUND_FREIGHT" -> {require(s.sourceId()!=null&&ids.add(s.sourceId()),"Duplicate source");freight++;}
                case "IMPORT_DUTY" -> {require(s.sourceId()!=null&&ids.add(s.sourceId()),"Duplicate source");duty++;}
                default -> throw new IllegalArgumentException("Unapproved source category");
            }
            money(s.receiptAmount());
            var original=money(s.originalClaimAmount());
            require(original.compareTo(slice(s,b,b.identity().claimId(),b.originalFrom(),b.originalTo()))==0,
                "Original component does not independently recompute");
            total=total.add(original);
        }
        require(po==1&&freight>0&&duty>0&&total.compareTo(money(b.originalCandidateAmount()))==0,
            "Original cost sources or amount incomplete");
    }
    private static String digest(Basis b) {
        // All tokens have fixed constrained alphabets (UUID, category enum,
        // ISO-like code, integer, exact amount), no delimiter injection.
        var i=b.identity();
        var fields=new ArrayList<>(List.of(b.version(),i.tenantId().toString(),i.marketplaceId().toString(),
            i.receiptId().toString(),i.claimId().toString(),i.salesOrderId().toString(),i.orderItemId().toString(),
            b.currency(),Integer.toString(b.basisQuantity()),Integer.toString(b.originalFrom()),
            Integer.toString(b.originalTo()),b.originalCandidateAmount()));
        b.sources().stream().sorted(ORDER).forEach(s->{
            fields.add(s.category());fields.add(s.sourceId()==null?"PO":s.sourceId().toString());
            fields.add(s.receiptAmount());fields.add(s.originalClaimAmount());
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                String.join("|",fields).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }

    public static Replay replay(Identity expectedScope,String expectedSha256,Basis b,List<Slice> allSlices) {
        identity(expectedScope);validate(b);
        require(expectedScope.equals(b.identity())&&expectedSha256!=null
            &&expectedSha256.matches("[0-9a-f]{64}")&&expectedSha256.equals(b.sha256())
            &&expectedSha256.equals(digest(b)),"Original candidate scope or fingerprint mismatch");
        require(allSlices!=null&&!allSlices.isEmpty()&&allSlices.size()<=1000,
            "Explicit refund population required; missing is not zero");
        var seen=new HashSet<UUID>();
        var sorted=new ArrayList<Slice>();
        for(var s:allSlices) {
            require(s!=null&&s.refundEventId()!=null&&seen.add(s.refundEventId())
                &&expectedScope.orderItemId().equals(s.orderItemId())
                &&s.fromInclusive()>=b.originalFrom()&&s.toExclusive()<=b.originalTo()
                &&s.toExclusive()>s.fromInclusive()
                &&s.reviewedQuantity()==s.toExclusive()-s.fromInclusive()
                &&"RESTOCKED_CLAIMED".equals(s.disposition()),
                "Invalid, repeated or non-restock refund slice");
            sorted.add(s);
        }
        sorted.sort(Comparator.comparingInt(Slice::fromInclusive));
        int end=b.originalFrom(),quantity=0;
        BigDecimal refund=zero();
        var events=new ArrayList<EventCandidate>();
        for(var s:sorted) {
            require(s.fromInclusive()>=end,"Overlapping original refund slices");
            BigDecimal amount=zero();
            for(var source:b.sources())amount=amount.add(slice(source,b,s.refundEventId(),s.fromInclusive(),s.toExclusive()));
            events.add(new EventCandidate(s.refundEventId(),s.reviewedQuantity(),amount.toPlainString()));
            quantity=Math.addExact(quantity,s.reviewedQuantity());
            end=s.toExclusive();refund=refund.add(amount);
        }
        int original=b.originalTo()-b.originalFrom();
        BigDecimal originalAmount=money(b.originalCandidateAmount());
        require(quantity<=original&&refund.compareTo(originalAmount)<=0,"Original refund capacity exceeded");
        return new Replay("LOCAL_REFUND_COST_SLICE_CANDIDATE_UNVERIFIED",expectedSha256,b.currency(),
            original,quantity,original-quantity,originalAmount.toPlainString(),refund.toPlainString(),
            originalAmount.subtract(refund).toPlainString(),List.copyOf(events),
            List.of("COMPLETE_PERSISTED_REFUND_POPULATION_UNPROVEN",
                "ORIGINAL_SALE_TIME_COST_AND_EXTERNAL_PHYSICAL_RETURN_UNVERIFIED",
                "G1_REFUND_COGS_RECOGNITION_NOT_APPROVED"),
            false,null,null);
    }
}
