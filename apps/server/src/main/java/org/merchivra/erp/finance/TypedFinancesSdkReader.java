package org.merchivra.erp.finance;

import com.amazon.SellingPartnerAPIAA.LWAException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.merchivra.erp.spapi.SpApiHttpException;
import org.merchivra.erp.spapi.SpApiOfficialModules;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiSellerScope;
import software.amazon.spapi.ApiException;
import software.amazon.spapi.models.finances.v2024_06_19.ListTransactionsResponse;

/**
 * Finances v2024-06-19 typed read. A transaction's TOTAL amount can be net of
 * many breakdowns: do not identify it as Sales, Refund or Fees by type heuristics.
 * Only the original signed total is staged as UNCLASSIFIED.
 */
public final class TypedFinancesSdkReader {
    @FunctionalInterface interface ReadCall {
        ListTransactionsResponse fetch(SpApiSellerScope scope,OffsetDateTime after,
            OffsetDateTime before,String cursor) throws Exception;
    }
    public record BreakdownNode(String path,String parent,String type,
                                String amount,String currency,boolean leaf) {}
    public record Reference(String kind,String value) {}
    public record Page(List<FinanceEventService.SourceEvent> events,String nextToken,
                       Map<String,List<BreakdownNode>> breakdowns,
                       Map<String,List<Reference>> references) {
        public Page(List<FinanceEventService.SourceEvent> events,String nextToken) {
            this(events,nextToken,Map.of(),Map.of());
        }
        public Page(List<FinanceEventService.SourceEvent> events,String nextToken,
                    Map<String,List<BreakdownNode>> breakdowns) {
            this(events,nextToken,breakdowns,Map.of());
        }
    }
    private final ReadCall call;

    public TypedFinancesSdkReader(SpApiOfficialSdkFactory factory) {
        Objects.requireNonNull(factory);
        var api=new SpApiOfficialModules(factory);
        this.call=(scope,after,before,cursor)->api.finances(scope)
            .listTransactions(after,before,scope.marketplaceId(),null,null,null,cursor);
    }
    TypedFinancesSdkReader(ReadCall call) { this.call=Objects.requireNonNull(call); }

    private static String stableKey(String raw) {
        if(raw==null || raw.isBlank() || raw.length()>512)
            throw new IllegalArgumentException("Stable transaction ID required");
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return "FIN-"+HexFormat.of().formatHex(digest);
        } catch(java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
    /**
     * Raw hierarchy only. Parent totals may include child totals, therefore
     * never add these rows to `finance_source_event` or the main aggregates.
     */
    private static void breakdowns(List<software.amazon.spapi.models.finances.v2024_06_19.Breakdown> source,
        String prefix,String parent,int depth,List<BreakdownNode> out) {
        if(source==null)return;
        if(depth>8 || source.size()>500 || out.size()+source.size()>2000)
            throw new IllegalArgumentException("Financial breakdown hierarchy exceeds safe limits");
        for(int i=0;i<source.size();i++) {
            var value=source.get(i);
            if(value==null)throw new IllegalArgumentException("Null financial breakdown");
            String path=prefix.isEmpty()?Integer.toString(i):prefix+"."+i;
            String type=value.getBreakdownType();
            if(type==null||type.isBlank()||type.length()>160
              || type.chars().anyMatch(Character::isISOControl))
                type="UNRECOGNIZED";
            String amount=null,currency=null;
            var money=value.getBreakdownAmount();
            if(money!=null) {
                if(money.getCurrencyAmount()!=null && money.getCurrencyCode()!=null) {
                    BigDecimal n=money.getCurrencyAmount();
                    if(n.precision()>20 || n.scale()>6 ||
                      !money.getCurrencyCode().matches("[A-Z]{3}"))
                        throw new IllegalArgumentException("Unsafe financial breakdown amount");
                    amount=n.toPlainString();currency=money.getCurrencyCode();
                } else if(money.getCurrencyAmount()!=null || money.getCurrencyCode()!=null)
                    throw new IllegalArgumentException("Partial financial breakdown money");
            }
            boolean leaf=value.getBreakdowns()==null || value.getBreakdowns().isEmpty();
            out.add(new BreakdownNode(path,parent,type,amount,currency,leaf));
            if(!leaf)breakdowns(value.getBreakdowns(),path,path,depth+1,out);
        }
    }

    public Page fetch(SpApiSellerScope scope,Instant after,Instant before,
                      String cursor) throws Exception {
        if(scope==null || !scope.approvedRoles().contains("Finance and Accounting"))
            throw new SecurityException("Independently reviewed Finances role required");
        if(after==null || before==null || !after.isBefore(before)
            || java.time.Duration.between(after,before).compareTo(java.time.Duration.ofDays(31))>0)
            throw new IllegalArgumentException("Bounded Finances time window required");
        if(cursor!=null && (cursor.isBlank() || cursor.length()>4096))
            throw new IllegalArgumentException("Invalid Finances cursor");
        ListTransactionsResponse response;
        try {
            response=call.fetch(scope,OffsetDateTime.ofInstant(after,ZoneOffset.UTC),
                OffsetDateTime.ofInstant(before,ZoneOffset.UTC),cursor);
        } catch(ApiException.RateLimitExceeded e) {
            throw new SpApiHttpException("listTransactions",429,"");
        } catch(ApiException e) {
            throw new SpApiHttpException("listTransactions",e.getCode(),"");
        } catch(LWAException e) {
            throw new SecurityException("LWA authentication failed");
        }
        if(response==null || response.getPayload()==null
          || response.getPayload().getTransactions()==null
          || response.getPayload().getTransactions().size()>500)
            throw new IllegalArgumentException("Invalid Finances response page");
        var result=new ArrayList<FinanceEventService.SourceEvent>();
        Set<String> seen=new HashSet<>();
        Map<String,List<BreakdownNode>> detail=new LinkedHashMap<>();
        Map<String,List<Reference>> references=new LinkedHashMap<>();
        for(var entry:response.getPayload().getTransactions()) {
            if(entry==null || entry.getTransactionId()==null
                || entry.getPostedDate()==null || entry.getTotalAmount()==null
                || entry.getTotalAmount().getCurrencyAmount()==null
                || entry.getTotalAmount().getCurrencyCode()==null)
                throw new IllegalArgumentException("Incomplete Finances transaction");
            var metadata=entry.getSellingPartnerMetadata();
            if(metadata!=null) {
                if(metadata.getMarketplaceId()!=null &&
                    !scope.marketplaceId().equals(metadata.getMarketplaceId()))
                    throw new IllegalArgumentException("Finances marketplace mismatch");
                if(metadata.getSellingPartnerId()!=null &&
                    !scope.sellerId().equals(metadata.getSellingPartnerId()))
                    throw new IllegalArgumentException("Finances Seller scope mismatch");
            }
            if(entry.getMarketplaceDetails()!=null
                && entry.getMarketplaceDetails().getMarketplaceId()!=null
                && !scope.marketplaceId().equals(entry.getMarketplaceDetails().getMarketplaceId()))
                throw new IllegalArgumentException("Finances marketplace details mismatch");
            String key=stableKey(entry.getTransactionId());
            if(!seen.add(key))
                throw new IllegalArgumentException("Duplicate transaction ID in source page");
            BigDecimal amount=entry.getTotalAmount().getCurrencyAmount();
            String currency=entry.getTotalAmount().getCurrencyCode();
            if(amount.precision()>20 || amount.scale()>6 ||
                !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("Unsafe source amount or currency");
            String orderId=null;
            var refs=new ArrayList<Reference>();
            var seenRefs=new HashSet<String>();
            if(entry.getRelatedIdentifiers()!=null) {
                for(var id:entry.getRelatedIdentifiers()) {
                    if(id==null)continue;
                    var kind=id.getRelatedIdentifierName();
                    if(kind==software.amazon.spapi.models.finances.v2024_06_19
                           .RelatedIdentifier.RelatedIdentifierNameEnum.SETTLEMENT_ID
                       || kind==software.amazon.spapi.models.finances.v2024_06_19
                           .RelatedIdentifier.RelatedIdentifierNameEnum.DISBURSEMENT_ID
                       || kind==software.amazon.spapi.models.finances.v2024_06_19
                           .RelatedIdentifier.RelatedIdentifierNameEnum.FINANCIAL_EVENT_GROUP_ID) {
                        String value=id.getRelatedIdentifierValue();
                        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))
                            throw new IllegalArgumentException("Unsafe Finances settlement reference");
                        String combined=kind.getValue()+":"+value;
                        if(seenRefs.add(combined))refs.add(new Reference(kind.getValue(),value));
                        if(refs.size()>30)
                            throw new IllegalArgumentException("Too many financial reference IDs");
                    }
                    if(id.getRelatedIdentifierName()==
                        software.amazon.spapi.models.finances.v2024_06_19
                            .RelatedIdentifier.RelatedIdentifierNameEnum.ORDER_ID) {
                        String value=id.getRelatedIdentifierValue();
                        if(value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))
                            orderId=value;
                    }
                }
            }
            // The original transaction type is not a verified business category.
            // Preserve its sanitized lexical code, but do not derive profit.
            String type=entry.getTransactionType();
            if(type==null || !type.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}"))
                type="UNRECOGNIZED";
            List<BreakdownNode> nodes=new ArrayList<>();
            breakdowns(entry.getBreakdowns(),"",null,0,nodes);
            detail.put(key,List.copyOf(nodes));
            references.put(key,List.copyOf(refs));
            result.add(new FinanceEventService.SourceEvent(key,"UNCLASSIFIED",
                amount.toPlainString(),currency,entry.getPostedDate(),orderId,type));
        }
        String next=response.getPayload().getNextToken();
        if(next!=null && (next.isBlank() || next.length()>4096))
            throw new IllegalArgumentException("Unsafe Finances cursor");
        return new Page(List.copyOf(result),next,Map.copyOf(detail),
            Map.copyOf(references));
    }
}
