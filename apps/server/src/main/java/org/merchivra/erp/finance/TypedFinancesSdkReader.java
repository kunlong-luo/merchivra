package org.merchivra.erp.finance;

import com.amazon.SellingPartnerAPIAA.LWAException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    public record Page(List<FinanceEventService.SourceEvent> events,String nextToken) {}
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
            if(entry.getRelatedIdentifiers()!=null) {
                for(var id:entry.getRelatedIdentifiers()) {
                    if(id==null)continue;
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
            result.add(new FinanceEventService.SourceEvent(key,"UNCLASSIFIED",
                amount.toPlainString(),currency,entry.getPostedDate(),orderId,type));
        }
        String next=response.getPayload().getNextToken();
        if(next!=null && (next.isBlank() || next.length()>4096))
            throw new IllegalArgumentException("Unsafe Finances cursor");
        return new Page(List.copyOf(result),next);
    }
}
